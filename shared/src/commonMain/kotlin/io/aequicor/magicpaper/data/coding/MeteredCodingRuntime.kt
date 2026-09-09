package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Passed down the flow, including the provider bridge's independent response reader. */
class RuntimeUsageContext(val ledger: UsageLedger, val owner: UsageScope) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RuntimeUsageContext>
}

class MeteredCodingRuntime(private val delegate: CodingRuntime, private val ledger: UsageLedger) : CodingRuntime by delegate {
    override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) =
        observe(session, profile?.forCoding(), delegate.run(project, session, prompt, profile, attachments))
    override fun runPlanning(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile) =
        observe(session, profile.forModel(), delegate.runPlanning(project, session, prompt, profile))

    private fun observe(session: CodingSession, profile: LlmProfile?, events: Flow<CodingEvent>): Flow<CodingEvent> = flow {
        val inherited = currentCoroutineContext()[UsageOwner]?.scope
        val owner = if (session.id.startsWith("planning-") && inherited?.conversationId != null) inherited else UsageScope.coding(session)
        val model = profile?.modelId.orEmpty()
        val subscription = profile?.provider == ProviderType.OPENAI_SUBSCRIPTION
        var native = session.piSessionId.ifBlank { session.id }
        val invocation = Id.new()
        var pending: UsageRecord? = null
        var compaction: String? = null
        var compactionSerial = 0
        var compactionActive = false
        fun base(id: String) = UsageRecord(id, scope = owner, provider = profile?.provider?.name.orEmpty(), model = model, subscription = subscription)
        suspend fun unknownContext() { owner.conversationId?.let { ledger.context(ContextUsageSnapshot(it, model, limit = profile?.advanced?.safeContextLimit?.toLong())) } }
        unknownContext()
        try {
            events.flowOn(UsageOwner(owner) + RuntimeUsageContext(ledger, owner)).collect { raw ->
                val event = if (raw is CodingEvent.Compaction && raw.status.id.isBlank()) {
                    if ((raw.status.phase == CompactionPhase.STARTED && !compactionActive) || compaction == null) compaction = "$invocation:${++compactionSerial}"
                    raw.copy(status = raw.status.copy(id = compaction!!))
                } else raw
                when (event) {
                    is CodingEvent.SessionStarted -> native = event.sessionId.ifBlank { native }
                    is CodingEvent.ModelRequest -> {
                        pending = base("pi-request:${event.id}")
                        ledger.record(pending!!)
                    }
                    is CodingEvent.UsageObserved -> if (event.accounting) {
                        val source = if (event.sourceId.startsWith("compaction:") && compaction != null)
                            "compaction:$compaction" else event.sourceId
                        val record = base("$native:$source").copy(tokens = event.tokens, completed = true,
                            cost = if (subscription) null else event.cost ?: profile?.modelCatalog?.firstOrNull { it.id == model }?.pricing?.estimate(event.tokens))
                        if (event.cumulative != null) ledger.cumulative("codex:$native", event.sourceId, event.cumulative, event.tokens, record)
                        else { ledger.record(record, pending?.id); pending = null }
                    }
                    is CodingEvent.ContextUpdated -> owner.conversationId?.let { ledger.context(ContextUsageSnapshot(it, model, event.used, event.limit, event.approximate)) }
                    is CodingEvent.Compaction -> {
                        unknownContext()
                        compactionActive = event.status.phase == CompactionPhase.STARTED
                    }
                    is CodingEvent.SearchObserved -> ledger.record(base("$native:search:${event.id}").copy(
                        kind = if (event.content) UsageKind.CONTENT else UsageKind.SEARCH, requests = event.requests, pages = event.pages, completed = true, contentRequests = if (event.content) event.requests else 0))
                    else -> Unit
                }
                emit(event)
            }
        } finally {
            compaction?.takeIf { compactionActive }?.let {
                // Consumers also settle running SYSTEM steps on interruption; never emit from a cancelled flow.
                withContext(NonCancellable) { unknownContext() }
            }
        }
    }
}
