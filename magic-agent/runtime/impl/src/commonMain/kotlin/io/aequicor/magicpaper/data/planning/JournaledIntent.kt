package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.JournalRecord
import io.aequicor.magicpaper.domain.PlanningRunContext
import kotlinx.coroutines.currentCoroutineContext
import io.aequicor.magicpaper.domain.PlanIntentStatus
import io.aequicor.magicpaper.domain.PlanJournalOperation
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Explicit completion: a non-local return cannot accidentally turn into success. */
internal class JournaledIntent(val record: JournalRecord) {
    private var status: PlanIntentStatus? = null
    private var primary: Throwable? = null

    fun complete() { status = PlanIntentStatus.COMPLETED }
    fun reject() { status = PlanIntentStatus.REJECTED }

    /** Local proof for the named external operation only; other owners retain their own admission outcomes. */
    fun beforeDispatch() { check(status == null); status = PlanIntentStatus.REJECTED }

    /** Call immediately before invoking the external port. Exceptions thereafter cannot prove rejection. */
    fun dispatching() { check(status == PlanIntentStatus.REJECTED); status = null }

    fun failed(failure: Throwable) {
        primary = failure
        // A transport exception cannot prove that the external operation was rejected.
    }

    suspend fun finish(store: PlanningStore, intent: JournalRecord) {
        val fields = mapOf("planId" to intent.stream, "operation" to intent.operation,
            "intentSeq" to intent.seq.toString(), "status" to (status?.name ?: "UNKNOWN"))
        try {
            withContext(NonCancellable) {
                val known = status
                if (known == null) store.markIntentUnknown(intent) else store.finishIntent(intent, known)
            }
            if (status == PlanIntentStatus.REJECTED)
                AppLog.error("planning.execution", "intent.rejected", fields = fields)
            else AppLog.info("planning.execution", if(status == null) "intent.unknown" else "intent.settled", fields)
        } catch (failure: Throwable) {
            // Log metadata only: storage exception messages can contain serialized content.
            AppLog.error("planning.execution", "intent.outcome-write.failed", fields = fields)
            val original = primary
            if (original == null) throw failure else if (original !== failure) original.addSuppressed(failure)
        }
    }
}

/**
 * The finally block covers non-local returns as well as coroutine cancellation. This scope
 * owns outcome diagnostics; the caller still owns the operation's user-visible failure.
 * An unrecordable outcome leaves the durable intent open and trips the storage guard.
 */
internal suspend inline fun <T> PlanningStore.withJournaledIntent(
    planId: String,
    operation: PlanJournalOperation,
    stageId: String = "",
    attemptId: String = "",
    block: JournaledIntent.() -> T,
): T {
    val intent = admitJournaledIntent(planId, operation, stageId, attemptId)
    val completion = JournaledIntent(intent)
    try {
        return completion.block()
    } catch (failure: Throwable) {
        completion.failed(failure)
        throw failure
    } finally {
        completion.finish(this, intent)
    }
}

private suspend fun PlanningStore.admitJournaledIntent(planId: String, operation: PlanJournalOperation, stageId: String, attemptId: String): JournalRecord {
    val ref = currentCoroutineContext()[PlanningRunContext]?.ref ?: checkNotNull(currentAdmission(planId)) { "Запуск не разрешён" }
    return beginIntent(planId, operation, stageId, attemptId, ref)
}
