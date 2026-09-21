package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.domain.Plan
import io.aequicor.magicpaper.domain.resolvePlan
import io.aequicor.magicpaper.domain.PlanningCheckpointStore
import io.aequicor.magicpaper.domain.DecisionCompiler
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/** Хранилище планов и досье моделей поверх KeyValueStore (та же схема, что у чатов). */
class JsonPlanningRepository(
    private val store: KeyValueStore,
    private val json: Json,
) : PlanningCheckpointStore {

    private val plansSerializer = ListSerializer(Plan.serializer())
    @Serializable private data class Checkpoint(val projectId: String, val plan: Plan?)

    private fun validatePlan(plan: Plan) {
        require(plan.id.isNotBlank() && plan.projectId.isNotBlank()) { "Повреждена идентичность плана" }
    }

    /** Only an unreadable document may use its backup; a decoded identity conflict is not repairable by dropping rows. */
    private fun decodePlans(raw: String?): List<Plan>? {
        if (raw == null) return null
        val plans = try { json.decodeFromString(plansSerializer, raw) }
        catch (_: SerializationException) { return null }
        plans.forEach(::validatePlan)
        require(plans.map { it.id }.distinct().size == plans.size) { "Повторяется идентичность плана" }
        return plans
    }

    private fun decodeCheckpoint(key: String, prefix: String, planKey: Boolean): Checkpoint? {
        val id = key.removePrefix(prefix)
        require(key.startsWith(prefix) && id.isNotBlank()) { "Повреждён ключ снимка плана" }
        val raw = store.read(key) ?: return null
        val checkpoint = try { json.decodeFromString<Checkpoint>(raw) }
        catch (_: SerializationException) { return null }
        require(checkpoint.projectId.isNotBlank()) { "Повреждена идентичность проекта" }
        if (!planKey) require(checkpoint.projectId == id) { "Снимок принадлежит другому проекту" }
        checkpoint.plan?.let { plan ->
            validatePlan(plan)
            require(plan.projectId == checkpoint.projectId && (!planKey || plan.id == id)) {
                "Снимок принадлежит другому плану или проекту"
            }
        }
        return checkpoint
    }

    override suspend fun plans(): List<Plan> {
        val checkpointKeys = store.keys("coding-plan-checkpoint-")
        val checkpoints = checkpointKeys.mapNotNull { key -> decodeCheckpoint(key, "coding-plan-checkpoint-", planKey = false) }
        val planCheckpointKeys = store.keys("coding-plan-v2-")
        val raw = store.read(KEY_PLANS)
        val primary = decodePlans(raw)
        val backup = if (primary == null) store.read("$KEY_PLANS-backup") else null
        val decoded = primary ?: decodePlans(backup)
            ?: if ((raw == null && backup == null) || checkpoints.isNotEmpty() || planCheckpointKeys.isNotEmpty()) emptyList()
            else error("Повреждены снимки планов; требуется восстановление данных")
        val restored = decoded.associateBy { it.id }.toMutableMap()
        // Retain ownership even when a later tombstone removes the visible row.
        val projectsByPlan = decoded.associate { it.id to it.projectId }.toMutableMap()
        fun retainIdentity(plan: Plan) {
            require(projectsByPlan[plan.id]?.let { it == plan.projectId } != false) { "План сменил идентичность проекта" }
            projectsByPlan[plan.id] = plan.projectId
        }
        val known = decoded.map { it.projectId } + checkpoints.map { it.projectId }
        require(checkpointKeys.all { it.removePrefix("coding-plan-checkpoint-") in known }) {
            "Повреждён журнал проекта, для которого нет резервного снимка"
        }
        checkpoints.forEach { checkpoint ->
            if (checkpoint.plan == null) restored.entries.removeAll { it.value.projectId == checkpoint.projectId }
            else {
                retainIdentity(checkpoint.plan)
                restored[checkpoint.plan.id] = checkpoint.plan
            }
        }
        planCheckpointKeys.forEach { key ->
            val checkpoint = decodeCheckpoint(key, "coding-plan-v2-", planKey = true) ?: error("Повреждён журнал плана")
            val id = key.removePrefix("coding-plan-v2-")
            if (checkpoint.plan == null) {
                // Old deletePlan(planId) encoded the exact lookup ID here instead of the project ID.
                // That alias is accepted only for a tombstone with the same key, never a live plan.
                require(projectsByPlan[id]?.let { it == checkpoint.projectId || id == checkpoint.projectId } != false) {
                    "Удалённый план принадлежит другому проекту"
                }
                restored.remove(id)
            } else {
                retainIdentity(checkpoint.plan)
                restored[id] = checkpoint.plan
            }
        }
        return restored.values.map(DecisionCompiler::migrate)
            .sortedByDescending { it.updatedAt }
    }

    override suspend fun planFor(projectId: String): Plan? =
        plans().resolvePlan(projectId)

    override suspend fun save(plan: Plan) {
        validatePlan(plan)
        // Each chat owns its plan; replacing one never removes sibling plans.
        val existing = plans()
        require(existing.none { it.id == plan.id && it.projectId != plan.projectId }) { "План принадлежит другому проекту" }
        val current = existing.filterNot { it.id == plan.id } + plan
        val previous = json.encodeToString(plansSerializer, existing)
        // Write-ahead checkpoint includes the operation journal; index/snapshot can be rebuilt.
        store.write("coding-plan-v2-${plan.id}", json.encodeToString(Checkpoint.serializer(), Checkpoint(plan.projectId, plan)))
        store.write("$KEY_PLANS-backup", previous)
        store.write(KEY_PLANS, json.encodeToString(plansSerializer, current))
    }

    override suspend fun deletePlan(projectId: String) {
        val existing = plans()
        val target = existing.resolvePlan(projectId) ?: return
        val current = existing.filterNot { it.id == target.id }
        store.write("coding-plan-v2-${target.id}", json.encodeToString(Checkpoint.serializer(), Checkpoint(target.projectId, null)))
        store.write(KEY_PLANS, json.encodeToString(plansSerializer, current))
    }

    override suspend fun wipe() {
        store.keys("coding-plan-checkpoint-").forEach { store.delete(it) }
        store.keys("coding-plan-v2-").forEach { store.delete(it) }
        store.delete(KEY_PLANS)
        store.delete("$KEY_PLANS-backup")
    }

    private companion object {
        const val KEY_PLANS = "coding-plans"
    }
}
