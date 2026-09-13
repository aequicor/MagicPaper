package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.util.Id

interface PlanningRepository {
    suspend fun plans(): List<Plan>

    /** Lookup by plan ID. Legacy project IDs are accepted only when unambiguous. */
    suspend fun planFor(projectId: String): Plan?

    /** Upsert by plan ID; other plans in the project are preserved. */
    suspend fun save(plan: Plan)

    suspend fun deletePlan(projectId: String)

    suspend fun dossiers(): List<ModelDossier>
    suspend fun saveDossier(dossier: ModelDossier)
    suspend fun wipe()
}