package io.aequicor.magicpaper.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.aequicor.magicpaper.util.Id
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Операционная модель сводит свежие результаты выбранного поиска.
 * Без источников возвращает неуспешный черновик, который не заменяет сохранённое досье.
 */


/** Транспорт служебных текстовых операций; выбор движка проекта к нему не применяется. */
val LlmProfile.completionEngineLabel: String get() = when (provider) {
    ProviderType.OPENAI_SUBSCRIPTION -> "Codex · подписка ChatGPT"
    ProviderType.OPENAI_COMPATIBLE -> "прямой API · OpenAI-совместимый"
    ProviderType.OPENROUTER -> "прямой API · OpenRouter"
    ProviderType.ANTHROPIC -> "прямой API · Anthropic"
    ProviderType.GOOGLE -> "прямой API · Google"
    ProviderType.ANTHROPIC_SUBSCRIPTION -> "Claude Code · подписка Claude"
}

fun AppSettings.descriptionSearchLabel(): String = when (searchProvider) {
    SearchProvider.GOOGLE -> "Google" + if (googleApiKey.isBlank() || googleSearchEngineId.isBlank()) " · не настроен" else ""
    SearchProvider.QUERIT -> "Querit.ai" + if (queritApiKey.isBlank()) " · не настроен" else ""
    SearchProvider.WIKIPEDIA -> "Wikipedia · только энциклопедия"
    SearchProvider.AUTO -> "Авто: " + buildList {
        if (googleApiKey.isNotBlank() && googleSearchEngineId.isNotBlank()) add("Google")
        if (queritApiKey.isNotBlank()) add("Querit.ai")
        add("Wikipedia")
    }.joinToString(" → ")
}
