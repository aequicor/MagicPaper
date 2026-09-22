package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.JournalRecord
import io.aequicor.magicpaper.data.storage.MachineTransitionLog
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.planning.PlanStateRecord
import io.aequicor.magicpaper.domain.planning.projectPlanState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val PLAN_STATE_OPERATION = "plan-state"
const val PLAN_INPUT_OPERATION = "planning-input"
private const val COMMIT_PREFIX = "plan-commit/1:"
private const val INPUT_PREFIX = "plan-commit/2:"

@Serializable
data class PlanInputCommit(val input: PlanningMachine.Input, val resetEpoch: Long, val evidence: String = "") {
    fun encode(): String = INPUT_PREFIX + Json.encodeToString(serializer(), this)
    companion object {
        fun from(record: JournalRecord): PlanInputCommit? = if (record.detail.startsWith(INPUT_PREFIX))
            Json.decodeFromString(serializer(), record.detail.removePrefix(INPUT_PREFIX)) else null
    }
}

/** State and its operational evidence share one atomic append. Old evidence stays readable. */
@Serializable
data class PlanJournalCommit(val state: PlanStateRecord, val evidence: String = "") {
    fun encode(): String = COMMIT_PREFIX + Json.encodeToString(serializer(), this)

    companion object {
        fun from(record: JournalRecord): PlanJournalCommit? {
            if (record.detail.startsWith(INPUT_PREFIX)) return null
            if (!record.detail.startsWith("plan-commit/")) {
                require(record.operation != PLAN_STATE_OPERATION) { "Missing plan state record" }
                return null
            }
            require(record.detail.startsWith(COMMIT_PREFIX)) { "Unsupported plan commit version" }
            return Json.decodeFromString(serializer(), record.detail.removePrefix(COMMIT_PREFIX))
        }
    }
}

fun JournalRecord.planEvidence(): JournalRecord =
    PlanInputCommit.from(this)?.let { copy(detail = it.evidence) }
        ?: PlanJournalCommit.from(this)?.let { copy(detail = it.evidence) } ?: this

/** Only accepted facts are replayed. Legacy operation records never rerun commands. */
fun projectPlanJournal(records: List<JournalRecord>): Plan? {
    val stream = records.firstOrNull()?.stream ?: return null
    return projectPlanningJournal(stream, records).plan
}

/** Legacy accepted deltas never create a run admission; version two replays only inputs. */
fun projectPlanningJournal(stream: String, records: List<JournalRecord>, resetEpoch: Long? = null): PlanningMachine.State {
    var state = PlanningMachine.initial(stream)
    var previous = 0L
    val inputs = mutableSetOf<String>()
    records.forEach { record ->
        require(record.stream == stream && record.seq > previous) { "Invalid plan journal order" }
        previous = record.seq
        val inputCommit = PlanInputCommit.from(record)
        if (inputCommit != null) {
            require(inputCommit.resetEpoch >= 0 && (resetEpoch == null || inputCommit.resetEpoch == resetEpoch)) { "Invalid plan reset epoch" }
            require(inputs.add(inputCommit.input.stamp.id) && inputCommit.input.stamp.at == record.at) { "Duplicate or invalid plan input" }
        }
        state = projectPlanningRecord(state, record)

    }
    return state
}

/** Incremental projection used by the strategy evidence reader. */
fun projectPlanningRecord(state: PlanningMachine.State, record: JournalRecord): PlanningMachine.State {
    require(state.id == record.stream)
    val input = PlanInputCommit.from(record)?.input?.let { acceptedInput ->
        normalizeLegacySkip(state, record, acceptedInput)
    } ?: PlanJournalCommit.from(record)?.let { commit ->
        require(commit.state.planId == record.stream) { "Plan state belongs to another stream" }
        val plan = projectPlanState(state.plan, commit.state)
        val stamp = PlanningMachine.Stamp("legacy:${record.seq}", record.at)
        if(state.plan == null) PlanningMachine.Fact.LegacyImported(plan, emptySet(), stamp)
        else PlanningMachine.Fact.LegacyCheckpoint(plan, stamp)
    } ?: return state
    val transition = PlanningMachine.reduce(state, input, replay = true)
    require(transition.rejection == null) { "Rejected persisted plan input" }
    MachineTransitionLog.replay(PlanningMachine.id, PlanningMachine.space, state, input, transition.state, transition.effects)
    return transition.state
}

/** Only old, already accepted input records may replace process-dependent UI hashes with exact proofs.
 * No history is rewritten and the normal reducer still checks the current run, attempt and criteria.
 */
private fun normalizeLegacySkip(state: PlanningMachine.State, record: JournalRecord,
    input: PlanningMachine.Input): PlanningMachine.Input {
    if(input !is PlanningMachine.Intent.SkipVerification || input.proofs != null) return input
    require(record.operation == PLAN_INPUT_OPERATION) { "Invalid legacy skip record" }
    val plan = requireNotNull(state.plan)
    val blockers = plan.blockingIssues(emptyList())
    require(blockers.isNotEmpty() && blockers.size == input.blockers.size && blockers.all { it.canSkipVerification }) {
        "Legacy skip no longer identifies the accepted verification"
    }
    val matched = blockers.map { blocker ->
        requireNotNull(input.blockers.singleOrNull(blocker::matchesLegacyMessageId)) { "Invalid legacy skip identity" }
    }
    require(matched.toSet() == input.blockers) { "Ambiguous legacy skip identity" }
    return input.copy(blockers = blockers.map { it.messageId }.toSet(),
        proofs = blockers.map { requireNotNull(it.verificationProof) }.toSet())
}
