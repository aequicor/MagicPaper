package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.domain.planning.*
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A run-owned, bounded diagnostic request. It never sends a command or executes model output. */
class PlanStrategyClassifier(
    private val store: PlanningStore,
    private val gateway: LlmGateway,
    private val profiles: LlmProfileRepository,
    private val settings: SettingsRepository,
    private val timeoutMillis: Long = 15_000,
) {
    @Serializable private data class CauseReply(val cause: PlanFailureCause)
    @Serializable private data class Evidence(val metrics: PlanStrategyMetrics, val currentIssue: IssueKind, val scheduledRetry: Int)

    /** Called before workspaces, native startup and clearing the previous issue. */
    suspend fun beforeRun(id: String): Boolean {
        if (store.failure.value != null) return false
        val cached = store.planFor(id) ?: return false
        if (cached.intent != ExecutionIntent.RUN || cached.stopping) return false
        if (!cached.mayContinueAutomaticRecovery()) return true
        val snapshot = store.journalPlan(id) ?: return false
        if (snapshot.plan.intent != ExecutionIntent.RUN || snapshot.plan.stopping) return false
        if (unsettledPlanIntents(snapshot.journal.records).isNotEmpty()) return false
        val facts = planStrategyFacts(snapshot.journal.records)
        val trigger = detectPlanStrategy(snapshot.plan, facts.samples) ?: return true
        val prior = facts.selections.lastOrNull { it.second.sourceSeq == trigger.sourceSeq }
        if (prior != null) return prior.second.strategy == PlanRecoveryStrategy.EXISTING_BACKOFF
        val fields = mapOf("planId" to id, "runId" to trigger.runId, "sourceSeq" to trigger.sourceSeq.toString())
        AppLog.info("planning.strategy", "classification.started", fields)
        val (cause, status) = try { classify(snapshot.plan, trigger.metrics, fields) }
        catch (cancelled: CancellationException) {
            AppLog.info("planning.strategy", "classification.cancelled", fields)
            throw cancelled
        }
        currentCoroutineContext().ensureActive()
        // A settings change during the request may narrow the palette as well.
        val retryLimit = settings.load().agentLimits.retries
        val strategy = selectPlanStrategy(snapshot.plan, cause, status, retryLimit)
        val selection = PlanStrategySelection(sourceSeq = trigger.sourceSeq, runId = trigger.runId, stageId = trigger.stageId,
            metrics = trigger.metrics, cause = cause, status = status, strategy = strategy)
        val accepted = try { store.recordStrategy(snapshot, selection, retryLimit) }
        catch (_: PlanningRevisionConflictException) { null }
        if (accepted == null) {
            AppLog.info("planning.strategy", "classification.superseded", fields)
            return false
        }
        AppLog.info("planning.strategy", "strategy.selected", fields + mapOf("cause" to cause.name, "strategy" to strategy.name, "status" to status.name))
        return strategy == PlanRecoveryStrategy.EXISTING_BACKOFF
    }

    private suspend fun classify(plan: Plan, metrics: PlanStrategyMetrics, fields: Map<String, String>): Pair<PlanFailureCause, PlanClassificationStatus> {
        val raw = try {
            val roster = profiles.load()
            val profile = plan.plannerSelection?.let { ProfileResolver.selection(it, roster) }
                ?: ProfileResolver.resolve(null as ChatSession?, settings.load(), roster)
            if (profile?.configured != true) {
                AppLog.error("planning.strategy", "classification.unavailable", fields = fields + ("reason" to "missing-profile"))
                return PlanFailureCause.UNKNOWN to PlanClassificationStatus.UNAVAILABLE
            }
            withTimeoutOrNull(timeoutMillis) {
                gateway.complete(profile, listOf(
                    LlmMessage(LlmChatRole.SYSTEM, PROMPT),
                    LlmMessage(LlmChatRole.USER, Json.encodeToString(Evidence.serializer(),
                        Evidence(metrics, checkNotNull(plan.issue).kind, checkNotNull(plan.issue).retries))),
                ))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            AppLog.error("planning.strategy", "classification.failed", fields = fields + ("causeType" to (failure::class.simpleName ?: "Exception")))
            return PlanFailureCause.UNKNOWN to PlanClassificationStatus.UNAVAILABLE
        }
        currentCoroutineContext().ensureActive()
        if (raw == null) {
            AppLog.error("planning.strategy", "classification.timeout", fields = fields)
            return PlanFailureCause.UNKNOWN to PlanClassificationStatus.UNAVAILABLE
        }
        return try {
            require(raw.length <= 512) { "Classifier response too large" }
            Json.decodeFromString(CauseReply.serializer(), raw).cause to PlanClassificationStatus.MODEL
        } catch (_: IllegalArgumentException) {
            AppLog.error("planning.strategy", "classification.invalid-response", fields = fields)
            PlanFailureCause.UNKNOWN to PlanClassificationStatus.INVALID_RESPONSE
        }
    }

    private companion object {
        const val PROMPT = """Classify the likely cause of repeated execution failures using only the supplied numeric journal metrics and typed issue kind.
Return exactly one JSON object with one field: {"cause":"TRANSIENT_TRANSPORT|PERSISTENT_CONFIGURATION|REJECTED_RESULT|CONFLICT|UNKNOWN"}.
Select one enum value, never the pipe-separated list. TRANSIENT_TRANSPORT means temporary provider/network failure; PERSISTENT_CONFIGURATION means invalid or unavailable configuration; REJECTED_RESULT means work failed acceptance; CONFLICT means integration/resource conflict. Use UNKNOWN when the evidence is insufficient.
Do not suggest an action, command, changed limit, permission, or retry time. No explanation or Markdown."""
    }
}
