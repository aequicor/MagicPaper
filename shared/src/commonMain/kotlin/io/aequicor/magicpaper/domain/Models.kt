package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

/** Роли в диалоге. */
@Serializable
enum class ChatRole { USER, AGENT }

/** Один результат поиска. */
@Serializable
data class SearchHit(
    val title: String,
    val url: String,
    val snippet: String = "",
    val provider: String = "",
)

/** Сообщение чата. */
@Serializable
data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val createdAt: Long,
    val sources: List<SearchHit> = emptyList(),
)

/** Сессия (свиток) чата. */
@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessage> = emptyList(),
    /**
     * Профиль подключения только для этого свитка.
     * null = глобальный активный профиль (см. [ProfileResolver]).
     */
    val llmProfileId: String? = null,
)

/** Доступные поисковые движки. */
@Serializable
enum class SearchProvider { AUTO, WIKIPEDIA, QUERIT, GOOGLE }

/** Настройки приложения. Сериализуются при экспорте профиля. */
@Serializable
data class AppSettings(
    /** Активный по умолчанию профиль подключения (см. [LlmProfile]). */
    val activeLlmProfileId: String = "",
    val searchProvider: SearchProvider = SearchProvider.AUTO,
    val queritApiKey: String = "",
    val googleApiKey: String = "",
    val googleSearchEngineId: String = "",
    /** Завершён ли ознакомительный тур (welcome-screen). */
    val onboardingDone: Boolean = false,
    // ---- Легаси-поля «одной модели» -------------------------------------
    // Сохраняются для совместимости со старыми файлами настроек; при первом
    // запуске переносятся в профиль подключением (см. ProfileMigrator).
    // Источник правды после миграции — профили, а не эти поля.
    val llmBaseUrl: String = "http://localhost:11434/v1",
    val llmApiKey: String = "",
    val llmModel: String = "llama3.2",
) {
    /** Совместимо: настроен либо профиль, либо легаси-тройка. */
    val llmConfigured: Boolean
        get() = activeLlmProfileId.isNotBlank() || (llmBaseUrl.isNotBlank() && llmModel.isNotBlank())
}

/** Состояние плагина: включён ли и его приватные настройки. */
@Serializable
data class PluginState(
    val id: String,
    val enabled: Boolean = true,
    val config: Map<String, String> = emptyMap(),
)

/** Полный переносимый профиль: настройки + плагины + история чатов + навыки + подключения. */
@Serializable
data class ProfileBundle(
    val version: Int = 2,
    val exportedAt: Long,
    val settings: AppSettings,
    val plugins: List<PluginState>,
    val sessions: List<ChatSession>,
    val skills: List<io.aequicor.magicpaper.domain.Skill> = emptyList(),
    val llmProfiles: List<LlmProfile> = emptyList(),
)
