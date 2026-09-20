package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.JournalRecord
import io.aequicor.magicpaper.domain.PlanIntentStatus
import io.aequicor.magicpaper.domain.PlanJournalOperation
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Explicit completion: a non-local return cannot accidentally turn into success. */
internal class JournaledIntent {
    private var status = PlanIntentStatus.INTERRUPTED
    private var primary: Throwable? = null

    fun complete() { status = PlanIntentStatus.COMPLETED }
    fun reject() { status = PlanIntentStatus.REJECTED }

    fun failed(failure: Throwable) {
        primary = failure
        if (failure !is CancellationException) reject()
    }

    suspend fun finish(store: PlanningStore, intent: JournalRecord) {
        val fields = mapOf("planId" to intent.stream, "operation" to intent.operation,
            "intentSeq" to intent.seq.toString(), "status" to status.name)
        try {
            withContext(NonCancellable) { store.finishIntent(intent, status) }
            if (status == PlanIntentStatus.REJECTED)
                AppLog.error("planning.execution", "intent.rejected", fields = fields)
            else AppLog.info("planning.execution", "intent.settled", fields)
        } catch (failure: Throwable) {
            // Log metadata only: storage exception messages can contain serialized content.
            AppLog.error("planning.execution", "intent.outcome-write.failed", fields = fields)
            val original = primary
            if (original == null) throw failure else original.addSuppressed(failure)
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
    val intent = beginIntent(planId, operation, stageId, attemptId)
    val completion = JournaledIntent()
    try {
        return completion.block()
    } catch (failure: Throwable) {
        completion.failed(failure)
        throw failure
    } finally {
        completion.finish(this, intent)
    }
}
