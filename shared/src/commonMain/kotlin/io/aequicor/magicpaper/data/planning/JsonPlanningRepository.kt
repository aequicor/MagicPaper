package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.domain.ModelDossier
import io.aequicor.magicpaper.domain.Plan
import io.aequicor.magicpaper.domain.PlanningRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Хранилище планов и досье моделей поверх KeyValueStore (та же схема, что у чатов). */
class JsonPlanningRepository(
    private val store: KeyValueStore,
    private val json: Json,
) : PlanningRepository {

    private val plansSerializer = ListSerializer(Plan.serializer())
    private val dossiersSerializer = ListSerializer(ModelDossier.serializer())

    override suspend fun plans(): List<Plan> {
        val raw = store.read(KEY_PLANS) ?: return emptyList()
        return runCatching { json.decodeFromString(plansSerializer, raw) }
            .getOrDefault(emptyList())
            .sortedByDescending { it.updatedAt }
    }

    override suspend fun planFor(projectId: String): Plan? =
        plans().firstOrNull { it.projectId == projectId }

    override suspend fun save(plan: Plan) {
        // На проект — один актуальный план: новый заменяет прежний.
        val current = plans().filterNot { it.projectId == plan.projectId } + plan
        store.write(KEY_PLANS, json.encodeToString(plansSerializer, current))
    }

    override suspend fun deletePlan(projectId: String) {
        val current = plans().filterNot { it.projectId == projectId }
        store.write(KEY_PLANS, json.encodeToString(plansSerializer, current))
    }

    override suspend fun dossiers(): List<ModelDossier> {
        val raw = store.read(KEY_DOSSIERS) ?: return emptyList()
        return runCatching { json.decodeFromString(dossiersSerializer, raw) }
            .getOrDefault(emptyList())
    }

    override suspend fun saveDossier(dossier: ModelDossier) {
        // Досье привязано к профилю 1:1 — перезаписываем по профилю.
        val current = dossiers().filterNot { it.profileId == dossier.profileId } + dossier
        store.write(KEY_DOSSIERS, json.encodeToString(dossiersSerializer, current))
    }

    override suspend fun wipe() {
        store.delete(KEY_PLANS)
        store.delete(KEY_DOSSIERS)
    }

    private companion object {
        const val KEY_PLANS = "coding-plans"
        const val KEY_DOSSIERS = "model-dossiers"
    }
}
