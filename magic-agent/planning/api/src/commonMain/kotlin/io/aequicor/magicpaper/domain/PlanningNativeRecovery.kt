package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

/** Journal-owned correlation. Logical attempt/turn identities never double as a transport request. */
@Serializable data class PlanningNativeRequest(
    val ref: PlanningMachine.RunRef, val intentSeq: Long, val journalEpoch: Long, val operation: PlanJournalOperation,
    val stageId: String, val attemptId: String, val expected: PlanningMachine.AttemptRef,
    val engine: CodingEngine, val sessionId: String, val requestId: String,
    val previous: PlanningNativeAcknowledgement? = null,
)

@Serializable data class PlanningNativeAcknowledgement(
    val attempt: NativeRunRecoveryAcknowledgement? = null,
    val noDispatch: NativeRunNoDispatchAcknowledgement? = null,
) {
    init { require((attempt == null) != (noDispatch == null)) }
    val id get() = attempt?.id ?: checkNotNull(noDispatch).id
    val engine get() = attempt?.predecessor?.engine ?: checkNotNull(noDispatch).proof.engine
    val sessionId get() = attempt?.predecessor?.sessionId ?: checkNotNull(noDispatch).proof.sessionId
}

@Serializable data class PlanningNativeEvidence(val requestId: String,
    val attempts: List<NativeRunRecoveryRef> = emptyList(), val noDispatch: NativeRunNoDispatchProof? = null) {
    init { require((attempts.isNotEmpty()) != (noDispatch != null)) }
}

/** One explicit decision covers exactly the pending journal revision displayed by inspection. */
@Serializable data class PlanningNativeRecoveryDecision(val id: String, val expectedPlanRevision: Long,
    val expectedJournalSeq: Long, val expectedJournalEpoch: Long, val pending: Set<Long>, val evidence: List<PlanningNativeEvidence>)

/** Observed terminal outcome with cleanup confirmed by the exact native owner. */
@Serializable data class PlanningNativeKnownOutcome(val ref: NativeRunRecoveryRef, val outcome: NativeRunOutcome) {
    init { require(outcome != NativeRunOutcome.UNKNOWN) }
}
@Serializable data class PlanningNativeRecoveryRelease(val requestId: String, val expected: PlanningMachine.AttemptRef,
    val known: List<PlanningNativeKnownOutcome> = emptyList())

@ConsistentCopyVisibility
data class PlanningNativeRecoveryState internal constructor(
    val requests: List<PlanningNativeRequest> = emptyList(),
    val decisions: Map<String, PlanningNativeRecoveryDecision> = emptyMap(),
    val acknowledgements: Map<String, List<PlanningNativeAcknowledgement>> = emptyMap(),
    val consumptions: Map<String, NativeRunRecoveryConsumption> = emptyMap(),
    val released: Set<String> = emptySet(),
    val releaseAuthorizations: Map<String, PlanningNativeRecoveryRelease> = emptyMap(),
) {
    fun pending(engine: CodingEngine, sessionId: String): PlanningNativeAcknowledgement? =
        requests.flatMap { acknowledgements[it.requestId].orEmpty() }.lastOrNull {
            it.engine == engine && it.sessionId == sessionId && it.id !in consumptions
        }
}

/** Validates a child proof without resolving the plan journal or granting any new work. */
private fun planningNativeReleaseProof(state: PlanningMachine.State, release: PlanningNativeRecoveryRelease): Pair<PlanningNativeRequest, StageAttempt> {
    val native = state.nativeRecovery
    val plan = checkNotNull(state.plan)
    val request = requireNotNull(native.requests.singleOrNull { it.requestId == release.requestId }) { "Запрос восстановления не найден" }
    require(request.requestId !in native.released) { "Восстановление уже закрыто" }
    require(native.requests.lastOrNull { it.stageId == request.stageId && it.attemptId == request.attemptId } == request) { "Запрос попытки изменился" }
    val evidence = requireNotNull(native.decisions[request.requestId]?.evidence?.singleOrNull { it.requestId == request.requestId })
    val acknowledgements = native.acknowledgements[request.requestId].orEmpty()
    require(release.known.map { it.ref }.distinct().size == release.known.size && release.known.all {
        it.ref in evidence.attempts && it.outcome != NativeRunOutcome.UNKNOWN
    }) { "Подтверждение результата принадлежит другой попытке" }
    require(if (evidence.noDispatch != null) release.known.isEmpty() && acknowledgements.any { it.noDispatch?.proof == evidence.noDispatch }
        else evidence.attempts.all { ref -> acknowledgements.any { it.attempt?.predecessor == ref } || release.known.any { it.ref == ref } }) { "Нет подтверждения исполнителя" }
    val attempt = requireNotNull(if (request.stageId.isBlank()) plan.finalAttempt else plan.milestones.singleOrNull { it.id == request.stageId }?.attempts?.lastOrNull())
    require(PlanningMachine.AttemptRef.from(attempt) == release.expected && attempt.id == request.attemptId) { "Попытка восстановления изменилась" }
    return request to attempt
}

/** A later legal checkpoint can obsolete a saved projection; it must not make the journal unreadable. */
internal fun planningNativeReadyReleases(state: PlanningMachine.State): List<PlanningNativeRecoveryRelease> {
    val native = state.nativeRecovery
    val plan = checkNotNull(state.plan)
    return native.releaseAuthorizations.values.filter { release ->
        val request = native.requests.singleOrNull { it.requestId == release.requestId }
        val attempt = request?.let {
            if (it.stageId.isBlank()) plan.finalAttempt else plan.milestones.singleOrNull { stage -> stage.id == it.stageId }?.attempts?.lastOrNull()
        }
        request != null && release.requestId !in native.released && request.intentSeq !in state.pendingOperations &&
            native.requests.lastOrNull { it.stageId == request.stageId && it.attemptId == request.attemptId } == request &&
            attempt?.let(PlanningMachine.AttemptRef::from) == release.expected
    }
}

/** Confirmation closes only the notice belonging to the latest acknowledged request of this attempt. */
internal fun planningNativeReleased(state: PlanningMachine.State, releases: List<PlanningNativeRecoveryRelease>): Pair<Plan, PlanningNativeRecoveryState> {
    var plan = checkNotNull(state.plan)
    val native = state.nativeRecovery
    require(releases.map { it.requestId }.distinct().size == releases.size)
    for (release in releases) {
        val (request, attempt) = planningNativeReleaseProof(state, release)
        require(request.intentSeq !in state.pendingOperations) { "Восстановление ещё не подтверждено" }
        if (attempt.error == PlanningRecoveryIssues.nativeUncertainty) {
            val cleared = attempt.copy(error = null)
            plan = if (request.stageId.isBlank()) plan.copy(finalAttempt = cleared) else plan.copy(milestones = plan.milestones.map {
                if (it.id == request.stageId) it.copy(attempts = it.attempts.dropLast(1) + cleared) else it
            })
            if (plan.issue == PlanningRecoveryIssues.nativeUncertainty) plan = plan.copy(issue = null)
        }
    }
    return plan to native.copy(released = native.released + releases.map { it.requestId })
}

@Serializable sealed interface PlanningNativeFact {
    @Serializable data class ReleaseAuthorized(val releases: List<PlanningNativeRecoveryRelease>) : PlanningNativeFact
    @Serializable data class RequestAdmitted(val request: PlanningNativeRequest) : PlanningNativeFact
    @Serializable data class Acknowledged(val requestId: String, val decisionId: String,
        val acknowledgement: PlanningNativeAcknowledgement) : PlanningNativeFact
    @Serializable data class ConsumptionObserved(val requestId: String,
        val consumption: NativeRunRecoveryConsumption) : PlanningNativeFact
}

/** Pure child-proof checks inside the existing plan machine, with no independent authority or effects. */
internal fun planningNativeObserved(state: PlanningMachine.State, fact: PlanningNativeFact): PlanningNativeRecoveryState {
    val old = state.nativeRecovery
    return when (fact) {
        is PlanningNativeFact.ReleaseAuthorized -> {
            require(fact.releases.isNotEmpty() && fact.releases.map { it.requestId }.distinct().size == fact.releases.size)
            for (release in fact.releases) {
                val (request, _) = planningNativeReleaseProof(state, release)
                require(request.intentSeq in state.pendingOperations) { "Намерение восстановления уже закрыто" }
                require(old.releaseAuthorizations[release.requestId].let { it == null || it.copy(expected = release.expected) == release }) { "Доказательство восстановления изменилось" }
            }
            old.copy(releaseAuthorizations = old.releaseAuthorizations + fact.releases.associateBy { it.requestId })
        }
        is PlanningNativeFact.RequestAdmitted -> {
            val request = fact.request
            val plan = checkNotNull(state.plan)
            require(state.run?.ref == request.ref && state.run.phase == PlanningMachine.RunPhase.RUNNING &&
                plan.intent == ExecutionIntent.RUN && !plan.stopping && state.pendingOperations.isEmpty()) { "Запуск не разрешён" }
            require(request.ref.planId == state.id && request.intentSeq > 0 && request.requestId.isNotBlank() &&
                request.sessionId.isNotBlank() && request.attemptId.isNotBlank() && old.requests.none { it.requestId == request.requestId }) { "Идентичность запроса недопустима" }
            val attempt = if(request.stageId.isBlank()) plan.finalAttempt else
                plan.milestones.singleOrNull { it.id == request.stageId }?.attempts?.lastOrNull()
            require(attempt?.id == request.attemptId) { "Попытка запроса изменилась" }
            val owned = checkNotNull(attempt)
            val expectedSession = when(request.operation) {
                PlanJournalOperation.AGENT_INTENT, PlanJournalOperation.FINAL_VERIFICATION_INTENT -> owned.sessionId
                PlanJournalOperation.CONFLICT_AGENT_INTENT -> "${owned.sessionId}-merge"
                PlanJournalOperation.DELIVERY_CONFLICT_INTENT -> "${owned.sessionId}-delivery"
                else -> error("Операция не отправляет native запрос")
            }
            require(request.sessionId == expectedSession) { "Сессия не принадлежит операции" }
            require(old.requests.none { it.intentSeq == request.intentSeq && it.journalEpoch == request.journalEpoch }) { "Намерение уже приняло native запрос" }
            require(PlanningMachine.AttemptRef.from(owned) == request.expected) { "Снимок попытки изменился" }
            require((attempt.engine ?: plan.engine)?.let { it == request.engine } != false) { "Движок запроса изменился" }
            require(request.previous == old.pending(request.engine, request.sessionId)) { "Решение восстановления изменилось" }
            old.copy(requests = old.requests + request)
        }
        is PlanningNativeFact.Acknowledged -> {
            val request = requireNotNull(old.requests.singleOrNull { it.requestId == fact.requestId }) { "Запрос восстановления не найден" }
            val decision = checkNotNull(old.decisions[fact.requestId])
            val evidence = requireNotNull(decision.evidence.singleOrNull { it.requestId == fact.requestId }) { "Доказательство восстановления не найдено" }
            val ack = fact.acknowledgement
            require(decision.id == fact.decisionId && ack.engine == request.engine && ack.sessionId == request.sessionId)
            val attemptAck = ack.attempt
            if(attemptAck != null) require(attemptAck.parentDecisionId == decision.id && attemptAck.predecessor in evidence.attempts)
            else checkNotNull(ack.noDispatch).let { require(it.parentDecisionId == decision.id && it.proof == evidence.noDispatch) }
            val previous = old.acknowledgements[fact.requestId].orEmpty()
            require(previous.none { it.id == ack.id || it.attempt?.predecessor == ack.attempt?.predecessor && ack.attempt != null ||
                it.noDispatch?.proof == ack.noDispatch?.proof && ack.noDispatch != null }) { "Подтверждение уже записано" }
            old.copy(acknowledgements = old.acknowledgements + (fact.requestId to (previous + ack)))
        }
        is PlanningNativeFact.ConsumptionObserved -> {
            val request = requireNotNull(old.requests.singleOrNull { it.requestId == fact.requestId }) { "Запрос восстановления не найден" }
            val ack = checkNotNull(request.previous)
            val proof = fact.consumption
            require(proof.acknowledgementId == ack.id && proof.engine == request.engine &&
                proof.sessionId == request.sessionId && proof.requestId == request.requestId) { "Подтверждение принадлежит другому запросу" }
            require(old.consumptions[ack.id].let { it == null || it == proof }) { "Подтверждение уже использовано другим запросом" }
            old.copy(consumptions = old.consumptions + (ack.id to proof))
        }
    }
}

internal fun planningNativeDecision(state: PlanningMachine.State, decision: PlanningNativeRecoveryDecision): PlanningNativeRecoveryState {
    val old = state.nativeRecovery
    require(state.plan?.revision == decision.expectedPlanRevision && state.pendingOperations == decision.pending && decision.pending.isNotEmpty()) { "Состояние восстановления изменилось" }
    require(decision.id.isNotBlank() && decision.evidence.isNotEmpty() && decision.evidence.map { it.requestId }.distinct().size == decision.evidence.size)
    decision.evidence.forEach { evidence ->
        val request = requireNotNull(old.requests.singleOrNull { it.requestId == evidence.requestId }) { "Запрос восстановления не найден" }
        require(request.intentSeq in decision.pending)
        require(evidence.attempts.distinct().size == evidence.attempts.size && evidence.attempts.all {
            it.engine == request.engine && it.sessionId == request.sessionId && it.requestId == request.requestId
        })
        evidence.noDispatch?.let { require(it.engine == request.engine && it.sessionId == request.sessionId && it.requestId == request.requestId) }
        // Lost reply recovery reuses the exact saved decision; inspection never creates a new one silently.
        old.decisions[request.requestId]?.let { require(it.id == decision.id && it.evidence == decision.evidence) { "Решение уже принято" } }
    }
    return old.copy(decisions = old.decisions + decision.evidence.associate { it.requestId to decision })
}
