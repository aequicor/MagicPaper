package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.domain.ModelDossier
import io.aequicor.magicpaper.domain.Plan
import io.aequicor.magicpaper.domain.resolvePlan
import io.aequicor.magicpaper.domain.PlanningRepository
import io.aequicor.magicpaper.domain.DecisionCompiler
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

/** Хранилище планов и досье моделей поверх KeyValueStore (та же схема, что у чатов). */
class JsonPlanningRepository(
    private val store: KeyValueStore,
    private val json: Json,
) : PlanningRepository {

    private val plansSerializer = ListSerializer(Plan.serializer())
    private val dossiersSerializer = ListSerializer(ModelDossier.serializer())
    @Serializable private data class Checkpoint(val projectId: String, val plan: Plan?)
    private fun checkpointKey(projectId: String) = "coding-plan-checkpoint-$projectId"

    override suspend fun plans(): List<Plan> {
        val checkpointKeys = store.keys("coding-plan-checkpoint-")
        val checkpoints = checkpointKeys.mapNotNull { key ->
            store.read(key)?.let { raw -> runCatching { json.decodeFromString<Checkpoint>(raw) }.getOrNull() }
        }
        val raw = store.read(KEY_PLANS)
        val decoded = raw?.let { runCatching { json.decodeFromString(plansSerializer, it) }.getOrNull() }
            ?: store.read("$KEY_PLANS-backup")?.let { runCatching { json.decodeFromString(plansSerializer, it) }.getOrNull() }
            ?: if (raw == null || checkpoints.isNotEmpty() || store.keys("coding-plan-v2-").isNotEmpty()) emptyList() else error("Повреждены снимки планов; требуется восстановление данных")
        val restored = decoded.associateBy { it.id }.toMutableMap()
        val known = decoded.map { it.projectId } + checkpoints.map { it.projectId }
        require(checkpointKeys.all { it.removePrefix("coding-plan-checkpoint-") in known }) {
            "Повреждён журнал проекта, для которого нет резервного снимка"
        }
        checkpoints.forEach { checkpoint ->
            if (checkpoint.plan == null) restored.entries.removeAll { it.value.projectId == checkpoint.projectId }
            else restored[checkpoint.plan.id] = checkpoint.plan
        }
        store.keys("coding-plan-v2-").forEach { key ->
            val checkpoint = store.read(key)?.let { raw -> json.decodeFromString<Checkpoint>(raw) } ?: error("Повреждён журнал плана")
            val id = key.removePrefix("coding-plan-v2-")
            if (checkpoint.plan == null) restored.remove(id) else restored[id] = checkpoint.plan
        }
        return restored.values.map(DecisionCompiler::migrate)
            .sortedByDescending { it.updatedAt }
    }

    override suspend fun planFor(projectId: String): Plan? =
        plans().resolvePlan(projectId)

    override suspend fun save(plan: Plan) {
        // Each chat owns its plan; replacing one never removes sibling plans.
        val existing = plans()
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
        store.write("coding-plan-v2-${target.id}", json.encodeToString(Checkpoint.serializer(), Checkpoint(projectId, null)))
        store.write(KEY_PLANS, json.encodeToString(plansSerializer, current))
    }

    override suspend fun dossiers(): List<ModelDossier> {
        val raw = store.read(KEY_DOSSIERS) ?: return emptyList()
        return runCatching { json.decodeFromString(dossiersSerializer, raw) }
            .getOrDefault(emptyList())
    }

    override suspend fun saveDossier(dossier: ModelDossier) {
        // Досье привязано к профилю 1:1 — перезаписываем по профилю.
        val current = dossiers().filterNot { it.profileId == dossier.profileId && it.modelId == dossier.modelId } + dossier
        store.write(KEY_DOSSIERS, json.encodeToString(dossiersSerializer, current))
    }

    override suspend fun wipe() {
        store.keys("coding-plan-checkpoint-").forEach { store.delete(it) }
        store.keys("coding-plan-v2-").forEach { store.delete(it) }
        store.delete(KEY_PLANS)
        store.delete("$KEY_PLANS-backup")
        store.delete(KEY_DOSSIERS)
    }

    private companion object {
        const val KEY_PLANS = "coding-plans"
        const val KEY_DOSSIERS = "model-dossiers"
    }
}
