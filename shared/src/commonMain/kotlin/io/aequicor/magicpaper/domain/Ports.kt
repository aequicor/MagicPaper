package io.aequicor.magicpaper.domain

/**
 * Порты доменного слоя (гексагональная архитектура).
 * Домен ничего не знает о Ktor, файлах и localStorage —
 * реализации приходят снаружи через конструкторы (DIP/SOLID).
 */

interface ChatRepository {
    suspend fun sessions(): List<ChatSession>
    suspend fun session(id: String): ChatSession?
    suspend fun save(session: ChatSession)
    suspend fun delete(id: String)
    suspend fun wipe()
}

interface SettingsRepository {
    suspend fun load(): AppSettings
    suspend fun save(settings: AppSettings)
    suspend fun pluginStates(): List<PluginState>
    suspend fun savePluginStates(states: List<PluginState>)
    suspend fun wipe()
}

data class DocArticle(val id: String, val title: String, val body: String)

data class DocMatch(val article: DocArticle, val score: Double)

/** Встроенная документация приложения. Используется агентом для ответов о программе. */
interface DocRepository {
    suspend fun articles(): List<DocArticle>
    suspend fun search(query: String, limit: Int = 3): List<DocMatch>
}

/** Поисковый движок (google / querit / wikipedia-фолбэк). */
interface SearchEngine {
    val provider: SearchProvider
    val displayName: String
    fun isConfigured(settings: AppSettings): Boolean
    suspend fun search(query: String, settings: AppSettings, limit: Int = 5): List<SearchHit>
}

/**
 * Шлюз к модели. Транспорт выбирается по типу провайдера в профиле
 * (см. RoutingLlmGateway) — потребители не знают о формате запроса.
 */
interface LlmGateway {
    suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String
    suspend fun completeWithActivity(profile: LlmProfile, messages: List<LlmMessage>, onActivity: (CodingStep) -> Unit): String {
        onActivity(CodingStep(CodingStepKind.INFO, "Ожидание ответа модели ${profile.shortLabel}. Подключение возвращает итоговый ответ."))
        return complete(profile, messages)
    }
}

/** Платформенный мост для сохранения/загрузки файла профиля. */
interface ProfileBridge {
    val supportsFilePicker: Boolean
    suspend fun export(json: String): Boolean
    suspend fun import(): String?
}
