package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.AdvancedLlmOptions
import io.aequicor.magicpaper.domain.LlmChatRole
import io.aequicor.magicpaper.domain.LlmMessage
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ReasoningCapability
import io.aequicor.magicpaper.domain.ReasoningEffort
import io.aequicor.magicpaper.domain.ResolvedEffort
import io.aequicor.magicpaper.domain.WireDialect
import io.aequicor.magicpaper.domain.budgetTokens
import io.aequicor.magicpaper.domain.resolveEffort
import kotlin.math.min
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Чистые сборщики тел запросов к провайдерам (без сети) — юнит-тестируются без моков.
 * Правила:
 *  - усилие кодируется только по объявленным моделью возможностям
 *    ([ReasoningCapability]): транспорту передают [ResolvedEffort], и гадать
 *    по имени модели не приходится. Выбор «по умолчанию провайдера» не
 *    порождает поля в запросе;
 *  - когда усилие активно, температура не подменяет его и не отправляется;
 *    [AdvancedLlmOptions.temperature] = null тоже не отправляется;
 *  - системные сообщения каждый формат несёт по-своему:
 *    OpenAI — ролью «system», Anthropic — полем «system», Google — «systemInstruction».
 */
object LlmPayloads {

    // ---- OpenAI-совместимый /chat/completions -----------------------------

    fun openAi(
        profile: LlmProfile,
        messages: List<LlmMessage>,
        capability: ReasoningCapability = ReasoningCapability.None,
    ): JsonObject {
        val resolved = profile.resolveEffort(capability)
        val a = profile.advanced
        return buildJsonObject {
            put("model", profile.modelId)
            put("stream", false)
            put("messages", buildJsonArray {
                messages.forEach { m ->
                    add(buildJsonObject { put("role", wireRole(m.role)); put("content", m.content) })
                }
            })
            resolved.level?.takeIf { it != ReasoningEffort.AUTO }?.let {
                put("reasoning_effort", it.wire)
            }
            if (!resolved.enabled) a.safeTemperature?.let { put("temperature", it) }
            put("max_tokens", a.safeMaxTokens)
            a.safeTopP?.let { put("top_p", it) }
        }
    }

    // ---- Anthropic /v1/messages -------------------------------------------

    fun anthropic(
        profile: LlmProfile,
        messages: List<LlmMessage>,
        capability: ReasoningCapability = ReasoningCapability.None,
    ): JsonObject {
        val resolved = profile.resolveEffort(capability)
        val controls = capability as? ReasoningCapability.Controls
        val a = profile.advanced
        val system = messages.filter { it.role == LlmChatRole.SYSTEM }.joinToString("\n\n") { it.content }
        val baseMax = a.safeMaxTokens

        // Мышление просим только при явном выборе уровня: «по умолчанию» и
        // «выключено» не должны менять поведение модели.
        val budget = if (resolved.enabled && controls?.dialect == WireDialect.BUDGET_TOKENS) {
            capability.budgetTokens(resolved.level)?.takeIf { it > 0 }
        } else {
            null
        }
        val adaptive = resolved.enabled && controls?.dialect == WireDialect.ADAPTIVE_EFFORT
        val thinking = adaptive || budget != null

        // С включённым бюджетом мышления Anthropic требует max_tokens строго
        // выше бюджета — при необходимости поднимаем потолок.
        val maxTokens = if (budget != null) maxOf(baseMax, budget + 1024) else baseMax

        return buildJsonObject {
            put("model", profile.modelId)
            put("max_tokens", maxTokens)
            if (system.isNotBlank()) put("system", system)
            put("messages", buildJsonArray {
                messages.filter { it.role != LlmChatRole.SYSTEM }.forEach { m ->
                    add(buildJsonObject {
                        put("role", if (m.role == LlmChatRole.ASSISTANT) "assistant" else "user")
                        put("content", m.content)
                    })
                }
            })
            if (adaptive) {
                put("thinking", buildJsonObject { put("type", "adaptive") })
                resolved.level?.takeIf { it != ReasoningEffort.AUTO }?.let {
                    put("output_config", buildJsonObject { put("effort", it.wire) })
                }
            } else if (budget != null) {
                put("thinking", buildJsonObject {
                    put("type", "enabled")
                    put("budget_tokens", budget)
                })
            } else {
                // С включённым мышлением Anthropic требует температуру 1,
                // поэтому свою температуру отправляем только без него.
                a.safeTemperature?.let { put("temperature", it) }
                a.safeTopP?.let { put("top_p", it) }
            }
        }
    }

    // ---- Google generateContent -------------------------------------------

    fun google(
        profile: LlmProfile,
        messages: List<LlmMessage>,
        capability: ReasoningCapability = ReasoningCapability.None,
    ): JsonObject {
        val resolved = profile.resolveEffort(capability)
        val controls = capability as? ReasoningCapability.Controls
        val a = profile.advanced
        val system = messages.filter { it.role == LlmChatRole.SYSTEM }.joinToString("\n\n") { it.content }
        val baseMax = a.safeMaxTokens

        return buildJsonObject {
            if (system.isNotBlank()) {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) })
                })
            }
            put("contents", buildJsonArray {
                messages.filter { it.role != LlmChatRole.SYSTEM }.forEach { m ->
                    add(buildJsonObject {
                        put("role", if (m.role == LlmChatRole.ASSISTANT) "model" else "user")
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", m.content) }) })
                    })
                }
            })
            put("generationConfig", buildJsonObject {
                if (!resolved.enabled) a.safeTemperature?.let { put("temperature", it) }
                put("maxOutputTokens", baseMax)
                a.safeTopP?.let { put("topP", it) }
                thinkingConfig(controls, resolved, baseMax)?.let { put("thinkingConfig", it) }
            })
        }
    }

    /**
     * Блок управления мышлением Gemini: бюджет токенов (2.5) или уровень (3).
     * `null` — блок не отправляем (выбор «по умолчанию» или ручек нет).
     */
    private fun thinkingConfig(
        controls: ReasoningCapability.Controls?,
        resolved: ResolvedEffort,
        maxOutput: Int,
    ): JsonObject? {
        val level = resolved.level ?: return null
        return when (controls?.dialect) {
            WireDialect.BUDGET_TOKENS -> {
                val budget = when (level) {
                    ReasoningEffort.NONE -> 0
                    ReasoningEffort.AUTO -> -1 // «динамически» — бюджет выбирает модель
                    else -> controls.budget?.let {
                        val raw = controls.budgetTokens(level) ?: return null
                        min(raw, maxOutput - 1).coerceAtLeast(0)
                    }
                }
                budget?.let { buildJsonObject { put("thinkingBudget", it) } }
            }
            WireDialect.THINKING_LEVEL -> buildJsonObject {
                put("thinkingLevel", if (level == ReasoningEffort.AUTO) "dynamic" else level.wire)
            }
            else -> null
        }
    }

    private fun wireRole(role: LlmChatRole): String = role.name.lowercase()
}
