package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.ChatRepository
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.LlmProfileRepository
import io.aequicor.magicpaper.domain.PluginState
import io.aequicor.magicpaper.domain.SettingsRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Реализация хранилища настроек поверх KeyValueStore. */
class JsonSettingsRepository(
    private val store: KeyValueStore,
    private val json: Json,
) : SettingsRepository {

    private val statesSerializer = ListSerializer(PluginState.serializer())

    override suspend fun load(): AppSettings {
        val raw = store.read(KEY_SETTINGS) ?: return AppSettings()
        return runCatching { json.decodeFromString<AppSettings>(raw) }.getOrDefault(AppSettings())
    }

    override suspend fun save(settings: AppSettings) {
        store.write(KEY_SETTINGS, json.encodeToString(AppSettings.serializer(), settings))
    }

    override suspend fun pluginStates(): List<PluginState> {
        val raw = store.read(KEY_PLUGINS) ?: return emptyList()
        return runCatching { json.decodeFromString(statesSerializer, raw) }.getOrDefault(emptyList())
    }

    override suspend fun savePluginStates(states: List<PluginState>) {
        store.write(KEY_PLUGINS, json.encodeToString(statesSerializer, states))
    }

    override suspend fun wipe() {
        store.delete(KEY_SETTINGS)
        store.delete(KEY_PLUGINS)
    }

    private companion object {
        const val KEY_SETTINGS = "settings"
        const val KEY_PLUGINS = "plugins"
    }
}

/** Реализация хранилища профилей подключения поверх KeyValueStore (ключ «llm_profiles»). */
class JsonLlmProfileRepository(
    private val store: KeyValueStore,
    private val json: Json,
) : LlmProfileRepository {

    private val serializer = ListSerializer(LlmProfile.serializer())

    override suspend fun load(): List<LlmProfile> {
        val raw = store.read(KEY_PROFILES) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    override suspend fun save(profile: LlmProfile) {
        val current = load().filterNot { it.id == profile.id } + profile
        store.write(KEY_PROFILES, json.encodeToString(serializer, current))
    }

    override suspend fun delete(id: String) {
        val rest = load().filterNot { it.id == id }
        store.write(KEY_PROFILES, json.encodeToString(serializer, rest))
    }

    override suspend fun replaceAll(profiles: List<LlmProfile>) {
        if (profiles.isEmpty()) {
            store.delete(KEY_PROFILES)
        } else {
            store.write(KEY_PROFILES, json.encodeToString(serializer, profiles))
        }
    }

    private companion object {
        const val KEY_PROFILES = "llm_profiles"
    }
}

/** Реализация хранилища чатов поверх KeyValueStore. */
class JsonChatRepository(
    private val store: KeyValueStore,
    private val json: Json,
) : ChatRepository {

    private val indexSerializer = ListSerializer(String.serializer())

    override suspend fun sessions(): List<ChatSession> {
        val ids = index()
        return ids.mapNotNull { id ->
            store.read(chatKey(id))?.let { raw ->
                runCatching { json.decodeFromString<ChatSession>(raw) }.getOrNull()
            }
        }.sortedByDescending { it.updatedAt }
    }

    override suspend fun session(id: String): ChatSession? {
        val raw = store.read(chatKey(id)) ?: return null
        return runCatching { json.decodeFromString<ChatSession>(raw) }.getOrNull()
    }

    override suspend fun save(session: ChatSession) {
        store.write(chatKey(session.id), json.encodeToString(ChatSession.serializer(), session))
        val ids = index().toMutableSet()
        if (ids.add(session.id)) {
            store.write(KEY_INDEX, json.encodeToString(indexSerializer, ids.toList()))
        }
    }

    override suspend fun delete(id: String) {
        store.delete(chatKey(id))
        val ids = index().filterNot { it == id }
        store.write(KEY_INDEX, json.encodeToString(indexSerializer, ids))
    }

    override suspend fun wipe() {
        index().forEach { store.delete(chatKey(it)) }
        store.delete(KEY_INDEX)
    }

    private fun index(): List<String> {
        val raw = store.read(KEY_INDEX) ?: return emptyList()
        return runCatching { json.decodeFromString(indexSerializer, raw) }.getOrDefault(emptyList())
    }

    private fun chatKey(id: String) = "chat:$id"

    private companion object {
        const val KEY_INDEX = "chats"
    }
}
