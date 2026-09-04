package io.aequicor.magicpaper.domain

/**
 * Единая точка разрешения «какой моделью отвечать»: чат, самообучение и
 * кодинг-агент используют один и тот же порядок, не решая его каждый по-своему.
 */
object ProfileResolver {
    /**
     * Профиль для запроса. Приоритет:
     *  1. переопределение свитка (session.llmProfileId),
     *  2. глобальный активный профиль (settings.activeLlmProfileId),
     *  3. первый настроенный профиль.
     * Ненастроенные профили пропускаются; если ни одного нет — null.
     */
    fun resolve(session: ChatSession?, settings: AppSettings, profiles: List<LlmProfile>): LlmProfile? {
        val wanted = session?.llmProfileId ?: settings.activeLlmProfileId
        return profiles.firstOrNull { it.id == wanted && it.configured }
            ?: profiles.firstOrNull { it.configured }
    }
}

/**
 * Одноразовая миграция легаси-настроек («одна тройка Base URL/ключ/модель»)
 * в первый профиль подключения. Чистая функция — проверяется без хранилища.
 */
object ProfileMigrator {
    const val LEGACY_ID = "legacy"

    /** Профиль из легаси-тройки; null, если тройка пустая (нечего мигрировать). */
    fun legacyProfile(settings: AppSettings): LlmProfile? {
        if (settings.llmBaseUrl.isBlank() && settings.llmModel.isBlank()) return null
        return LlmProfile(
            id = LEGACY_ID,
            name = if (settings.llmBaseUrl.contains("11434")) "Ollama (локально)" else "Мой сервер",
            provider = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = settings.llmBaseUrl,
            apiKey = settings.llmApiKey,
            modelId = settings.llmModel,
        )
    }
}
