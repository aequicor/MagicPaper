package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Passed down the flow, including the provider bridge's independent response reader. */
class RuntimeUsageContext(val ledger: UsageLedger, val owner: UsageScope, val observation: UsageObservation) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RuntimeUsageContext>
}

class MeteredCodingRuntime(
    private val delegate: CodingRuntime,
    private val ledger: UsageLedger,
    private val plans: PlanUsageMonitor? = null,
) : CodingRuntime by delegate {
    override fun runChat(session: ChatSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) =
        observe(CodingSession(session.id, "chat-${session.id}", session.title, session.createdAt,
            piSessionId = session.nativeSessionId, engine = session.engine), profile?.forModel(),
            delegate.runChat(session, prompt, profile, attachments), UsageScope.chat(session.id))

    override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) =
        observe(session, profile?.forCoding(), delegate.run(project, session, prompt, profile, attachments))
    override fun runPlanning(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile) =
        observe(session, profile.forModel(), delegate.runPlanning(project, session, prompt, profile))

    private fun observe(session: CodingSession, profile: LlmProfile?, events: Flow<CodingEvent>, ownerOverride: UsageScope? = null): Flow<CodingEvent> = flow {
        val observation = ledger.captureObservation()
        val inherited = currentCoroutineContext()[UsageOwner]?.scope
        val owner = ownerOverride ?: if (session.id.startsWith("planning-") && inherited?.conversationId != null) inherited else UsageScope.coding(session)
        val model = profile?.modelId.orEmpty()
        val subscription = profile?.provider?.subscription == true
        var native = session.piSessionId.ifBlank { session.id }
        val invocation = Id.new()
        var pending: UsageRecord? = null
        var compaction: String? = null
        var compactionSerial = 0
        var compactionActive = false
        fun base(id: String) = UsageRecord(id, scope = owner, provider = profile?.provider?.name.orEmpty(), model = model, subscription = subscription)
        // The window an engine reported for this model outranks the profile's limit: an engine that keeps its own
        // conversation (Claude Code) works with its own window, and a fresh run or a compaction must not undo that.
        suspend fun unknownContext() { owner.conversationId?.let { id ->
            val reported = ledger.state.value.contexts[id]?.takeIf { it.model == model }?.limit
            ledger.context(observation, ContextUsageSnapshot(id, model, limit = reported ?: profile?.advanced?.safeContextLimit?.toLong()))
        } }
        unknownContext()
        try {
            events.flowOn(UsageOwner(owner) + RuntimeUsageContext(ledger, owner, observation)).collect { raw ->
                val event = if (raw is CodingEvent.Compaction && raw.status.id.isBlank()) {
                    if ((raw.status.phase == CompactionPhase.STARTED && !compactionActive) || compaction == null) compaction = "$invocation:${++compactionSerial}"
                    raw.copy(status = raw.status.copy(id = compaction!!))
                } else raw
                when (event) {
                    is CodingEvent.SessionStarted -> native = event.sessionId.ifBlank { native }
                    is CodingEvent.ModelRequest -> {
                        pending = base("pi-request:${event.id}")
                        ledger.record(observation, pending!!)
                    }
                    is CodingEvent.UsageObserved -> if (event.accounting) {
                        val source = if (event.sourceId.startsWith("compaction:") && compaction != null)
                            "compaction:$compaction" else event.sourceId
                        val record = base("$native:$source").copy(tokens = event.tokens, completed = true,
                            cost = if (subscription) null else event.cost ?: profile?.modelCatalog?.firstOrNull { it.id == model }?.pricing?.estimate(event.tokens))
                        if (event.cumulative != null) ledger.cumulative(observation, "codex:$native", event.sourceId, event.cumulative!!, event.tokens, record)
                        else { ledger.record(observation, record, pending?.id); pending = null }
                    }
                    is CodingEvent.ContextUpdated -> owner.conversationId?.let { ledger.context(observation, ContextUsageSnapshot(it, model, event.used, event.limit, event.approximate)) }
                    is CodingEvent.Compaction -> {
                        unknownContext()
                        compactionActive = event.status.phase == CompactionPhase.STARTED
                    }
                    is CodingEvent.SearchObserved -> ledger.record(observation, base("$native:search:${event.id}").copy(
                        kind = if (event.content) UsageKind.CONTENT else UsageKind.SEARCH, requests = event.requests, pages = event.pages, completed = true, contentRequests = if (event.content) event.requests else 0))
                    // An engine signed in by its own CLI runs without a profile; a profile names the plan it bills.
                    is CodingEvent.PlanUsageObserved -> {
                        if (profile == null || profile.provider == event.usage.provider) plans?.observe(event.usage)
                        return@collect
                    }
                    else -> Unit
                }
                emit(event)
            }
        } finally {
            // Only the ChatGPT plan can be asked between requests; a run on it, even a refused one, moved its figures.
            profile?.provider?.let { plans?.refresh(it) }
            compaction?.takeIf { compactionActive }?.let {
                // Consumers also settle running SYSTEM steps on interruption; never emit from a cancelled flow.
                withContext(NonCancellable) { unknownContext() }
            }
        }
    }
}
