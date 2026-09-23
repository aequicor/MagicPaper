package io.aequicor.magicpaper.domain

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.Serializable

@Serializable data class NativeRunRecoveryRef(val engine: CodingEngine, val sessionId: String, val requestId: String, val attempt: Int)
@Serializable enum class NativeRunOutcome { NOT_DISPATCHED, UNKNOWN, SUCCEEDED, FAILED }
@Serializable enum class NativeRunTermination { NOT_STARTED, LIVE, STOPPED, UNKNOWN }
@Serializable data class NativeRunRecoveryAcknowledgement(val id: String, val predecessor: NativeRunRecoveryRef, val parentDecisionId: String)
@Serializable data class NativeRunNoDispatchProof(val engine: CodingEngine, val sessionId: String, val requestId: String,
    val proofId: String, val journalGeneration: String)
@Serializable data class NativeRunNoDispatchAcknowledgement(val id: String, val proof: NativeRunNoDispatchProof, val parentDecisionId: String)
data class NativeRunNoDispatchItem(val proof: NativeRunNoDispatchProof, val acknowledgement: NativeRunNoDispatchAcknowledgement?)
@Serializable data class NativeRunRecoveryConsumption(val acknowledgementId: String, val engine: CodingEngine,
    val sessionId: String, val requestId: String)
data class NativeRunRecoveryItem(val ref: NativeRunRecoveryRef, val outcome: NativeRunOutcome, val termination: NativeRunTermination,
    val acknowledgement: NativeRunRecoveryAcknowledgement?)
data class NativeRunRecoverySnapshot(val items: List<NativeRunRecoveryItem>, val persistenceUnknown: Boolean,
    val noDispatch: List<NativeRunNoDispatchItem> = emptyList(), val consumptions: List<NativeRunRecoveryConsumption> = emptyList())
class NativeRunRecoveryRequired(val recovery: NativeRunRecoverySnapshot, cause: Throwable? = null) : IllegalStateException(
    "Исход предыдущего запуска не подтверждён. Проверьте сохранённый результат перед новым запросом.", cause)

/**
 * Every recorded process has exited and every outcome nobody can know carries the user's explicit decision: the rule the
 * native journal applies when it admits the next run of the same session. A plain reconcile stays stricter and demands a
 * known outcome, for callers that must not act on an uncertain one.
 */
val NativeRunRecoverySnapshot.decided: Boolean get() = !persistenceUnknown && items.all {
    it.termination == NativeRunTermination.STOPPED && (it.outcome != NativeRunOutcome.UNKNOWN || it.acknowledgement != null)
}

/** Inspection never repeats work. Stopping proves cleanup, not the outcome of executed commands. */
interface NativeRunRecovery {
    suspend fun inspect(sessionId: String): NativeRunRecoverySnapshot
    suspend fun stop(ref: NativeRunRecoveryRef): NativeRunRecoverySnapshot
    suspend fun acknowledge(ref: NativeRunRecoveryRef, parentDecisionId: String): NativeRunRecoveryAcknowledgement
    suspend fun acknowledgeNoDispatch(proof: NativeRunNoDispatchProof, parentDecisionId: String): NativeRunNoDispatchAcknowledgement
}

/** A durable explicit parent decision, scoped to the next fresh run instead of persisted on a session. */
class NativeRunRecoveryBinding(val acknowledgement: NativeRunRecoveryAcknowledgement? = null,
    val noDispatchAcknowledgement: NativeRunNoDispatchAcknowledgement? = null) : AbstractCoroutineContextElement(Key) {
    init { require((acknowledgement == null) != (noDispatchAcknowledgement == null)) { "Exactly one native recovery decision is required" } }
    companion object Key : CoroutineContext.Key<NativeRunRecoveryBinding>
}
