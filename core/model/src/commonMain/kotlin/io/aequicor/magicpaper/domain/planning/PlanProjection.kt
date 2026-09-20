package io.aequicor.magicpaper.domain.planning

import io.aequicor.magicpaper.domain.Plan
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Accepted facts, after the domain transition and message checkpoint have been evaluated.
 * The initial record carries the existing Plan schema verbatim. Later records replace only
 * changed top-level fields; an unchanged array prefix is retained and only its suffix is stored.
 * This keeps growing message/journal histories out of every subsequent record without a second
 * schema for every Plan field. This is a persistence format, not a command or a merge policy.
 */
@Serializable
data class PlanStateRecord(
    val version: Int = 1,
    val planId: String,
    val expectedRevision: Long? = null,
    val fields: JsonObject,
    val appended: Map<String, List<JsonElement>> = emptyMap(),
) {
    fun encode(): String = planRecordJson.encodeToString(serializer(), this)

    companion object {
        fun decode(payload: String): PlanStateRecord = planRecordJson.decodeFromString(serializer(), payload)
    }
}

private val planRecordJson = Json { encodeDefaults = true }

/** The caller fixes IDs, revision, time and generated message events before it appends this fact. */
fun recordPlanState(previous: Plan?, next: Plan): PlanStateRecord {
    require(next.id.isNotBlank() && next.revision >= 0) { "Invalid plan revision" }
    require(previous == null || (next.id == previous.id && next.projectId == previous.projectId &&
        previous.revision < Long.MAX_VALUE && next.revision == previous.revision + 1)) { "Invalid plan transition identity" }
    val after = planRecordJson.encodeToJsonElement(Plan.serializer(), next).jsonObject
    if (previous == null) return PlanStateRecord(planId = next.id, fields = after)
    val before = planRecordJson.encodeToJsonElement(Plan.serializer(), previous).jsonObject
    val fields = mutableMapOf<String, JsonElement>()
    val appended = mutableMapOf<String, List<JsonElement>>()
    after.forEach { (key, value) ->
        val old = before[key]
        if (value != old) {
            if (old is JsonArray && value is JsonArray && value.size > old.size && value.take(old.size) == old)
                appended[key] = value.drop(old.size)
            else fields[key] = value
        }
    }
    return PlanStateRecord(planId = next.id, expectedRevision = previous.revision,
        fields = JsonObject(fields), appended = appended)
}

/** Replay performs no I/O, clocks, ID generation, message scheduling or execution. */
fun projectPlanState(previous: Plan?, record: PlanStateRecord): Plan {
    require(record.version == 1) { "Unsupported plan record version" }
    require(record.planId.isNotBlank()) { "Missing plan record identity" }
    require(record.expectedRevision == previous?.revision) { "Plan record revision conflict" }
    require(previous == null || previous.id == record.planId) { "Plan record belongs to another stream" }
    require(record.fields.keys.intersect(record.appended.keys).isEmpty()) { "Ambiguous plan record field" }
    require(previous != null || record.appended.isEmpty()) { "Initial plan record cannot append fields" }
    val before = previous?.let { planRecordJson.encodeToJsonElement(Plan.serializer(), it).jsonObject }
    val fields = before.orEmpty().toMutableMap()
    record.appended.forEach { (key, suffix) ->
        val old = before?.get(key)
        require(old is JsonArray && suffix.isNotEmpty()) { "Invalid appended plan field" }
        fields[key] = JsonArray(old + suffix)
    }
    fields.putAll(record.fields)
    val next = planRecordJson.decodeFromJsonElement(Plan.serializer(), JsonObject(fields))
    require(next.id == record.planId && next.revision >= 0) { "Invalid projected plan identity" }
    require(previous == null || (next.projectId == previous.projectId && previous.revision < Long.MAX_VALUE &&
        next.revision == previous.revision + 1)) { "Invalid projected plan revision" }
    return next
}
