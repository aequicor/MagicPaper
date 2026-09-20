package io.aequicor.magicpaper.domain

/**
 * What a journal entry records about an operation.
 *
 * The distinction is load-bearing for recovery. An [INTENT] entry is written *before* its
 * effect runs, so a plan whose journal ends on one may have executed that effect without the
 * application learning the result. Recovery must resolve such an entry against real evidence
 * — a native receipt or a person confirming the outcome — and must never repeat the effect
 * because the journal looks unfinished.
 */
enum class JournalEntryKind { INTENT, OUTCOME, NOTICE }

/**
 * The closed set of plan journal operations.
 *
 * The entry keeps [PlanJournalEntry.operation] as a string because saved plans contain it:
 * the wire form is data already on disk and does not change. What is closed is the set a
 * writer may produce, so a comparison is a value check rather than a spelling contest.
 * A plan written by an older version may carry an unknown string; it reads back as `null`
 * and is treated as a notice, never mistaken for a settled outcome.
 */
enum class PlanJournalOperation(val wire: String, val kind: JournalEntryKind) {
    STOP_INTENT("stop-intent", JournalEntryKind.INTENT),
    STOP_CONFIRMED("stop-confirmed", JournalEntryKind.OUTCOME),
    STOP_SIGNAL_ERROR("stop-signal-error", JournalEntryKind.NOTICE),

    PREPARE_INTENT("prepare-intent", JournalEntryKind.INTENT),
    STAGE_WORKSPACE_INTENT("stage-workspace-intent", JournalEntryKind.INTENT),
    AGENT_INTENT("agent-intent", JournalEntryKind.INTENT),
    CAPTURE_INTENT("capture-intent", JournalEntryKind.INTENT),
    MERGE_INTENT("merge-intent", JournalEntryKind.INTENT),
    CONFLICT_AGENT_INTENT("conflict-agent-intent", JournalEntryKind.INTENT),
    DELIVERY_CONFLICT_INTENT("delivery-conflict-intent", JournalEntryKind.INTENT),
    FINAL_VERIFICATION_INTENT("final-verification-intent", JournalEntryKind.INTENT),
    APPLY_INTENT("apply-intent", JournalEntryKind.INTENT),

    STAGE_COMPLETE("stage-complete", JournalEntryKind.OUTCOME),
    APPLY_COMPLETE("apply-complete", JournalEntryKind.OUTCOME),

    USER_SKIP_VERIFICATION("user-skip-verification", JournalEntryKind.NOTICE),
    GIT_UNAVAILABLE("git-unavailable", JournalEntryKind.NOTICE),
    SESSION_PROJECTION_PENDING("session-projection-pending", JournalEntryKind.NOTICE);

    companion object {
        private val byWire = entries.associateBy { it.wire }
        fun of(wire: String): PlanJournalOperation? = byWire[wire]
    }
}

/** Writers use this form; the wire string is produced here and nowhere else. */
fun PlanJournalEntry(
    id: String,
    at: Long,
    operation: PlanJournalOperation,
    stageId: String = "",
    attemptId: String = "",
    detail: String = "",
) = PlanJournalEntry(id, at, stageId, attemptId, operation.wire, detail)

/** Null for a plan written before this operation existed; such an entry is only a notice. */
val PlanJournalEntry.operationKind: PlanJournalOperation? get() = PlanJournalOperation.of(operation)

fun PlanJournalEntry.records(operation: PlanJournalOperation): Boolean = this.operation == operation.wire

/**
 * Stopping is compared entry by entry when a person answers a recovery request. The check
 * stays on the wire prefix rather than the closed set: a stop written by an older version
 * must still take part in the comparison, or two different run states could compare equal
 * and a stale recovery answer would be accepted. Writers are closed; this reader is not.
 */
val PlanJournalEntry.describesStop: Boolean get() = operation.startsWith(STOP_PREFIX)

private const val STOP_PREFIX = "stop-"

