package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Составитель досье моделей: «модель ищет информацию о себе один раз».
 * Запрос строится из имени модели и провайдера; результаты поиска идут
 * в контекст модели, она сводит их в описание сильных сторон и оценку.
 * Без модели или при сбое — эвристическое досье по имени модели
 * (помечается «без модели»), которое пользователь может отредактировать.
 */
class DossierResearcher(
    private val gateway: LlmGateway,
    private val searchEngine: SearchEngine,
    private val json: Json = DEFAULT_JSON,
) {

    @Serializable
    private data class RawDossier(val strengths: String = "", val rating: Int = 0)

    /** Собирает досье для профиля. [profile] — модель, которая сводит информацию. */
    suspend fun research(
        target: LlmProfile,
        profile: LlmProfile?,
        settings: AppSettings,
    ): ModelDossier {
        if (target.modelId.isBlank()) {
            return fallback(target, "У профиля не указано имя модели — заполните описание вручную.")
        }
        if (profile == null || !profile.configured) {
            return fallback(target, "Без модели: описание по имени модели, подправьте вручную.")
        }
        return runCatching { modelDossier(target, profile, settings) }
            .getOrElse { fallback(target, "Поиск не удался (${it.message}), описание по имени модели.") }
    }

    private suspend fun modelDossier(
        target: LlmProfile,
        profile: LlmProfile,
        settings: AppSettings,
    ): ModelDossier {
        val query = "AI model ${target.modelId} strengths capabilities ${providerName(target)}"
        val hits = searchEngine.search(query, settings, limit = 5)
        val context = if (hits.isEmpty()) {
            ""
        } else {
            "Результаты поиска:\n" + hits.joinToString("\n\n") { hit ->
                "[${hit.provider}] ${hit.title}\n${hit.snippet}\n${hit.url}"
            }
        }
        val messages = buildList {
            add(LlmMessage(LlmChatRole.SYSTEM, RESEARCH_PROMPT))
            if (context.isNotBlank()) add(LlmMessage(LlmChatRole.SYSTEM, context))
            add(LlmMessage(LlmChatRole.USER, "Модель: ${target.modelId}, провайдер: ${providerName(target)}"))
        }
        val raw = gateway.complete(profile, messages)
        val dossier = parse(raw)
        require(dossier.strengths.isNotBlank()) { "Модель вернула пустое описание" }
        return ModelDossier(
            id = "",
            profileId = target.id,
            strengths = dossier.strengths.trim(),
            rating = dossier.rating.coerceIn(1, 5),
            source = DossierSource.WEB,
            references = hits.map { it.url }.distinct().take(5),
        )
    }

    /** Эвристическое досье без сети и модели: по имени модели. */
    private fun fallback(target: LlmProfile, note: String): ModelDossier {
        val id = target.modelId.substringAfterLast('/').lowercase()
        val strengths = buildString {
            append("Модель ${target.modelId} (${providerName(target)}).")
            when {
                "code" in id || "coder" in id -> append(" Похожа на специализированную на программировании.")
                "chat" in id || "instruct" in id -> append(" Похожа на диалоговую/инструкционную модель.")
                id.startsWith("gpt-") || id.startsWith("o") || id.startsWith("claude") ->
                    append(" Похожа на универсальную флагманскую модель.")
            }
            append(" Описание не проверено — уточните вручную.")
        }
        return ModelDossier(
            id = "",
            profileId = target.id,
            strengths = strengths,
            rating = 0,
            source = DossierSource.HEURISTIC,
            note = note,
        )
    }

    /** Вырезаем первый JSON-объект из ответа модели. */
    private fun parse(raw: String): RawDossier {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "В ответе модели нет JSON" }
        return json.decodeFromString(RawDossier.serializer(), raw.substring(start, end + 1))
    }

    private fun providerName(profile: LlmProfile): String = when (profile.provider) {
        ProviderType.OPENAI_COMPATIBLE -> "OpenAI-совместимый сервер (${profile.name})"
        ProviderType.OPENROUTER -> "OpenRouter"
        ProviderType.ANTHROPIC -> "Anthropic"
        ProviderType.GOOGLE -> "Google"
    }

    private companion object {
        val DEFAULT_JSON = Json { ignoreUnknownKeys = true }

        val RESEARCH_PROMPT = """
            Ты составляешь досье на ИИ-модель для сравнения моделей в команде.
            Опираясь на результаты поиска и свои знания, ответь строго одним
            JSON-объектом без пояснений: {"strengths": "в каких областях модель
            сильна (2-4 предложения, по-русски)", "rating": число 1-5 — насколько
            она сильна и универсальна относительно других моделей}.
        """.trimIndent()
    }
}
