package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.EffortLevel
import io.aequicor.magicpaper.domain.LlmMessage
import io.aequicor.magicpaper.domain.LlmProfile
import kotlin.math.min
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Чистые сборщики тел запросов к провайдерам (без сети) — юнит-тестируются без моков.
 * Правила:
 *  - пустые значения [io.aequicor.magicpaper.domain.AdvancedSettings] не попадают
 *    в запрос («по умолчанию провайдера»);
 *  - усилие применяется только к моделям, которые его поддерживают
 *    (флаг [supportsEffort] в сигнатуре): нативные поля —
 *    reasoning_effort (OpenAI), thinking (Anthropic), thinkingConfig (Google);
 *    для остальных — температурный пресет;
 *  - системные сообщения каждый формат несёт по-своему:
 *    OpenAI — ролью «system», Anthropic — полем «system», Google — «systemInstruction».
 */
object LlmPayloads {

    /** Температурный пресет усилия для моделей без нативного управления усилием. */
    fun temperatureForEffort(effort: EffortLevel): Double = when (effort) {
        EffortLevel.LOW -> 0.2
        EffortLevel.MEDIUM -> 0.7
        EffortLevel.HIGH -> 1.1
    }

    // ---- OpenAI-совместимый /chat/completions -----------------------------

    fun openAi(profile: LlmProfile, messages: List<LlmMessage>, supportsEffort: Boolean): JsonObject = buildJsonObject {
        put("model", profile.modelId)
        put("stream", false)
        put("messages", buildJsonArray {
            messages.forEach { m -> add(buildJsonObject { put("role", m.role); put("content", m.content) }) }
        })
        val a = profile.advanced
        if (supportsEffort) {
            put("reasoning_effort", effortToken(profile.effort))
            // Температуру к рассуждающим моделям добавляем только если её явно задали.
            a.temperature?.let { put("temperature", it) }
        } else {
            put("temperature", a.temperature ?: temperatureForEffort(profile.effort))
        }
        a.maxTokens?.let { put("max_tokens", it) }
        a.topP?.let { put("top_p", it) }
    }

    private fun effortToken(effort: EffortLevel): String = when (effort) {
        EffortLevel.LOW -> "low"
        EffortLevel.MEDIUM -> "medium"
        EffortLevel.HIGH -> "high"
    }

    // ---- Anthropic /v1/messages -------------------------------------------

    /** Бюджет токенов «размышления» по уровню усилия. */
    fun anthropicThinkingBudget(effort: EffortLevel): Int = when (effort) {
        EffortLevel.LOW -> 1024
        EffortLevel.MEDIUM -> 4096
        EffortLevel.HIGH -> 16000
    }

    fun anthropic(profile: LlmProfile, messages: List<LlmMessage>, supportsEffort: Boolean = false): JsonObject {
        val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        val budget = anthropicThinkingBudget(profile.effort)
        // С включённым мышлением Anthropic требует max_tokens строго выше бюджета —
        // при необходимости поднимаем потолок.
        val maxTokens = if (supportsEffort) {
            maxOf(profile.advanced.maxTokens ?: ANTHROPIC_DEFAULT_MAX_TOKENS, budget + 1024)
        } else {
            profile.advanced.maxTokens ?: ANTHROPIC_DEFAULT_MAX_TOKENS
        }
        return buildJsonObject {
            put("model", profile.modelId)
            put("max_tokens", maxTokens)
            if (system.isNotBlank()) put("system", system)
            put("messages", buildJsonArray {
                messages.filter { it.role != "system" }.forEach { m ->
                    add(buildJsonObject {
                        put("role", if (m.role == "assistant") "assistant" else "user")
                        put("content", m.content)
                    })
                }
            })
            if (supportsEffort) {
                // С включённым thinking Anthropic требует температуру 1,
                // поэтому свою температуру не отправляем вовсе.
                put("thinking", buildJsonObject {
                    put("type", "enabled")
                    put("budget_tokens", budget)
                })
            } else {
                profile.advanced.temperature?.let { put("temperature", it) }
            }
            profile.advanced.topP?.let { put("top_p", it) }
        }
    }

    // ---- Google generateContent -------------------------------------------

    /** Бюджет токенов «размышления» Gemini по уровню усилия. */
    fun googleThinkingBudget(effort: EffortLevel): Int = when (effort) {
        EffortLevel.LOW -> 1024
        EffortLevel.MEDIUM -> 4096
        EffortLevel.HIGH -> 16000
    }

    fun google(profile: LlmProfile, messages: List<LlmMessage>, supportsEffort: Boolean = false): JsonObject {
        val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        return buildJsonObject {
            if (system.isNotBlank()) {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) })
                })
            }
            put("contents", buildJsonArray {
                messages.filter { it.role != "system" }.forEach { m ->
                    add(buildJsonObject {
                        put("role", if (m.role == "assistant") "model" else "user")
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", m.content) }) })
                    })
                }
            })
            put("generationConfig", buildJsonObject {
                val a = profile.advanced
                put("temperature", a.temperature ?: temperatureForEffort(profile.effort))
                a.maxTokens?.let { put("maxOutputTokens", it) }
                a.topP?.let { put("topP", it) }
                if (supportsEffort) {
                    // Бюджет мышления не должен превышать потолок вывода.
                    val budget = googleThinkingBudget(profile.effort)
                    val capped = a.maxTokens?.let { min(budget, it - 1).coerceAtLeast(0) } ?: budget
                    put("thinkingConfig", buildJsonObject { put("thinkingBudget", capped) })
                }
            })
        }
    }

    private const val ANTHROPIC_DEFAULT_MAX_TOKENS = 4096
}
