package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.JournalRecord
import io.aequicor.magicpaper.domain.Plan
import io.aequicor.magicpaper.domain.planning.PlanStateRecord
import io.aequicor.magicpaper.domain.planning.projectPlanState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val PLAN_STATE_OPERATION = "plan-state"
private const val COMMIT_PREFIX = "plan-commit/1:"

/** State and its operational evidence share one atomic append. Old evidence stays readable. */
@Serializable
internal data class PlanJournalCommit(val state: PlanStateRecord, val evidence: String = "") {
    fun encode(): String = COMMIT_PREFIX + Json.encodeToString(serializer(), this)

    companion object {
        fun from(record: JournalRecord): PlanJournalCommit? {
            if (!record.detail.startsWith("plan-commit/")) {
                require(record.operation != PLAN_STATE_OPERATION) { "Missing plan state record" }
                return null
            }
            require(record.detail.startsWith(COMMIT_PREFIX)) { "Unsupported plan commit version" }
            return Json.decodeFromString(serializer(), record.detail.removePrefix(COMMIT_PREFIX))
        }
    }
}

internal fun JournalRecord.planEvidence(): JournalRecord =
    PlanJournalCommit.from(this)?.let { copy(detail = it.evidence) } ?: this

/** Only accepted facts are replayed. Legacy operation records never rerun commands. */
internal fun projectPlanJournal(records: List<JournalRecord>): Plan? {
    var plan: Plan? = null
    var previous = 0L
    val stream = records.firstOrNull()?.stream
    records.forEach { record ->
        require(record.stream == stream && record.seq > previous) { "Invalid plan journal order" }
        previous = record.seq
        PlanJournalCommit.from(record)?.let { commit ->
            require(commit.state.planId == record.stream) { "Plan state belongs to another stream" }
            plan = projectPlanState(plan, commit.state)
        }
    }
    return plan
}
