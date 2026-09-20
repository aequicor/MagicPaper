package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

    INTENT_OUTCOME("intent-outcome", JournalEntryKind.OUTCOME),
    INTENT_RECONCILED("intent-reconciled", JournalEntryKind.OUTCOME),
    STRATEGY_SELECTED("strategy-selected", JournalEntryKind.NOTICE),
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


/**
 * What an operation was about: the stage and the attempt it belonged to.
 *
 * The event journal stores a record's detail as an opaque string, because a journal that knew
 * the words would have to change whenever a feature learned a new one. This is the plan's own
 * schema for that string — serialized rather than joined, so an identifier can hold any
 * character without the reader having to guess where one field ends.
 */
@Serializable
data class PlanJournalSubject(val stage: String = "", val attempt: String = "") {
    companion object {
        private val json = Json { encodeDefaults = true }

        /** An operation about the plan as a whole carries no subject and encodes to nothing. */
        fun encode(stageId: String, attemptId: String): String =
            if (stageId.isBlank() && attemptId.isBlank()) ""
            else json.encodeToString(serializer(), PlanJournalSubject(stageId, attemptId))

        /** Anything unrecognizable reads as no subject: a record still counts, it just says less. */
        fun decode(detail: String): PlanJournalSubject =
            if (detail.isBlank()) PlanJournalSubject()
            else runCatching { json.decodeFromString(serializer(), detail) }.getOrDefault(PlanJournalSubject())
    }
}

/** Completion of the local effect, not proof that every external command succeeded.
 * INTERRUPTED preserves uncertainty; it never authorizes replay of an external effect.
 */
@Serializable
enum class PlanIntentStatus { COMPLETED, REJECTED, INTERRUPTED }

/** Sequence identity distinguishes repeated turns and nested effects in the same attempt. */
@Serializable
data class PlanIntentOutcome(val intentSeq: Long, val status: PlanIntentStatus, val strategySeq: Long? = null) {
    fun encode(): String = Json.encodeToString(serializer(), this)

    companion object {
        // Unlike optional display metadata, invalid outcome evidence must fail closed.
        fun decode(detail: String): PlanIntentOutcome = Json.decodeFromString(serializer(), detail)
    }
}

/** Recovery records evidence, never invents whether the interrupted effect succeeded. */
@Serializable
enum class PlanRecoveryAuthority { NATIVE_RECEIPT, USER }

@Serializable
data class PlanIntentReconciliation(val intentSeq: Long, val authority: PlanRecoveryAuthority) {
    fun encode(): String = Json.encodeToString(serializer(), this)
    companion object {
        fun decode(detail: String): PlanIntentReconciliation = Json.decodeFromString(serializer(), detail)
    }
}
