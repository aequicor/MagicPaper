package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.logging.AppLog

/**
 * Reconciles a session whose owner goes on with it: another run, a delivery or a history change. An outcome the user already
 * decided about no longer holds the session, as the native journal admits its next run; an undecided one still refuses.
 */
suspend fun CodingRuntime.reconcileDecided(sessionId: String) {
    try { reconcile(sessionId) }
    catch (unknown: NativeRunRecoveryRequired) {
        if (!unknown.recovery.decided) throw unknown
        AppLog.debug("coding", "reconcile.decided", mapOf("sessionId" to sessionId,
            "count" to unknown.recovery.items.count { it.outcome == NativeRunOutcome.UNKNOWN }.toString()))
    }
}
