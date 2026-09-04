package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.domain.CodingMessage
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingProjectRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Хранилище проектов и журналов поверх KeyValueStore (та же схема, что у чатов). */
class JsonCodingProjectRepository(
    private val store: KeyValueStore,
    private val json: Json,
) : CodingProjectRepository {

    private val projectIndexSerializer = ListSerializer(String.serializer())
    private val projectsSerializer = ListSerializer(CodingProject.serializer())
    private val messagesSerializer = ListSerializer(CodingMessage.serializer())

    override suspend fun all(): List<CodingProject> {
        val raw = store.read(KEY_PROJECTS) ?: return emptyList()
        return runCatching { json.decodeFromString(projectsSerializer, raw) }
            .getOrDefault(emptyList())
            .sortedByDescending { it.createdAt }
    }

    override suspend fun save(project: CodingProject) {
        val projects = all().filterNot { it.id == project.id } + project
        store.write(KEY_PROJECTS, json.encodeToString(projectsSerializer, projects))
    }

    override suspend fun delete(id: String) {
        val projects = all().filterNot { it.id == id }
        store.write(KEY_PROJECTS, json.encodeToString(projectsSerializer, projects))
        store.delete(logKey(id))
    }

    override suspend fun messages(projectId: String): List<CodingMessage> {
        val raw = store.read(logKey(projectId)) ?: return emptyList()
        return runCatching { json.decodeFromString(messagesSerializer, raw) }
            .getOrDefault(emptyList())
    }

    override suspend fun saveMessages(projectId: String, messages: List<CodingMessage>) {
        store.write(logKey(projectId), json.encodeToString(messagesSerializer, messages))
    }

    override suspend fun wipe() {
        all().forEach { store.delete(logKey(it.id)) }
        store.delete(KEY_PROJECTS)
    }

    private fun logKey(projectId: String) = "coding-log:$projectId"

    private companion object {
        const val KEY_PROJECTS = "coding-projects"
    }
}
