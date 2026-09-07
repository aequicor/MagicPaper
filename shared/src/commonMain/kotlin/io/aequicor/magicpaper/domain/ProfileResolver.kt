package io.aequicor.magicpaper.domain

/**
 * Единая точка разрешения «какой моделью отвечать»: чат, самообучение и
 * кодинг-агент используют один и тот же порядок, не решая его каждый по-своему.
 */
object ProfileResolver {
    /**
     * Профиль для запроса. Приоритет:
     *  1. переопределение свитка/сессии (profileId),
     *  2. глобальный активный профиль (settings.activeLlmProfileId),
     *  3. первый настроенный профиль.
     * Ненастроенные профили пропускаются; если ни одного нет — null.
     */
    fun resolve(profileId: String?, settings: AppSettings, profiles: List<LlmProfile>): LlmProfile? {
        if (profileId == null && settings.defaultModel != null) return selection(settings.defaultModel, profiles)
        val wanted = profileId ?: settings.activeLlmProfileId
        val profile = profiles.firstOrNull { it.id == wanted && it.configured }
            ?: profiles.firstOrNull { it.configured } ?: return null
        return profile.forModel()
    }

    fun selection(choice: ModelSelection, profiles: List<LlmProfile>): LlmProfile? =
        profiles.firstOrNull { it.id == choice.profileId && it.connectionConfigured }
            ?.takeIf { choice.modelId in it.displayModels || choice.modelId == it.modelId || it.modelCatalog.any { model -> model.id == choice.modelId } }
            ?.forModel(choice.modelId, choice.effort)

    fun resolve(session: ChatSession?, settings: AppSettings, profiles: List<LlmProfile>): LlmProfile? {
        session?.modelSelection?.let { return selection(it, profiles) }
        return resolve(session?.llmProfileId, settings, profiles)
    }

    fun favoriteDefault(settings: AppSettings, profiles: List<LlmProfile>, coding: Boolean = false): ModelSelection? {
        val eligible = profiles.filter { it.connectionConfigured && (!coding || it.supportsCoding) }
        val main = resolve(null as String?, settings, eligible)
        if (main != null && eligible.any { it.id == main.id && main.selectionKey in it.displayModels })
            return ModelSelection(main.id, main.selectionKey, main.effortSelectionFor())
        val first = eligible.firstOrNull { it.id == settings.activeLlmProfileId && it.displayModels.isNotEmpty() }
            ?: eligible.firstOrNull { it.displayModels.isNotEmpty() } ?: return null
        return ModelSelection(first.id, first.displayModels.first())
    }

    fun coding(session: CodingSession, project: CodingProject?, settings: AppSettings, profiles: List<LlmProfile>): LlmProfile? {
        val choice = session.modelSelection ?: project?.modelSelection
        if (choice != null) return selection(choice, profiles)?.takeIf { it.supportsCoding }
        val profile = profiles.firstOrNull { it.id == session.llmProfileId && it.connectionConfigured }
        return (profile?.forCoding() ?: resolve(null as String?, settings, profiles))?.takeIf { it.supportsCoding }
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
