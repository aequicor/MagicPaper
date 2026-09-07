package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.AdvancedLlmOptions
import io.aequicor.magicpaper.domain.Attachment
import io.aequicor.magicpaper.domain.AttachmentKind
import io.aequicor.magicpaper.domain.LlmChatRole
import io.aequicor.magicpaper.domain.LlmMessage
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ReasoningCapability
import io.aequicor.magicpaper.domain.ReasoningEffort
import io.aequicor.magicpaper.domain.ResolvedEffort
import io.aequicor.magicpaper.domain.WireDialect
import io.aequicor.magicpaper.domain.budgetTokens
import io.aequicor.magicpaper.domain.decodeText
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
 *    OpenAI — ролью «system», Anthropic — полем «system», Google — «systemInstruction»;
 *  - вложения: изображения идут мультимодальными блоками (формат зависит от провайдера),
 *    текстовые файлы подмешиваются в текст сообщения.
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
            a.extraParameters.filterKeys { it in io.aequicor.magicpaper.domain.CUSTOM_MODEL_PARAMETERS }.forEach { (key, value) -> put(key, value) }
            put("model", profile.modelId)
            put("stream", false)
            put("messages", buildJsonArray {
                messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", wireRole(m.role))
                        if (m.role == LlmChatRole.USER && m.attachments.isNotEmpty()) {
                            if (hasImageAttachments(m.attachments)) {
                                put("content", openAiContent(m))
                            } else {
                                put("content", finalText(m))
                            }
                        } else {
                            put("content", m.content)
                        }
                    })
                }
            })
            resolved.level?.takeIf { it != ReasoningEffort.AUTO }?.let {
                if (profile.provider == io.aequicor.magicpaper.domain.ProviderType.OPENROUTER) {
                    put("reasoning", buildJsonObject { put("effort", it.wire) })
                } else put("reasoning_effort", it.wire)
            }
            if (!resolved.enabled) a.safeTemperature?.let { put("temperature", it) }
            if (a.sendMaxTokens) put("max_tokens", a.safeMaxTokens)
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
        // «выключено» не должны менять поведение модели. Бюджет считаем без
        // обрезки по потолку вывода: ниже потолок поднимется под бюджет.
        val budget = if (resolved.enabled && controls?.dialect == WireDialect.BUDGET_TOKENS) {
            capability.budgetTokens(resolved.level)?.takeIf { it > 0 }?.let { requested ->
                if (profile.modelLibraryVersion >= 1) {
                    require(baseMax >= 2048) { "Для рассуждения нужен лимит ответа не меньше 2048 токенов." }
                    minOf(requested, baseMax - 1024)
                } else requested
            }
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
            a.extraParameters["top_k"]?.let { put("top_k", it) }
            a.extraParameters["stop"]?.let { put("stop_sequences", it) }
            put("max_tokens", maxTokens)
            if (system.isNotBlank()) put("system", system)
            put("messages", buildJsonArray {
                messages.filter { it.role != LlmChatRole.SYSTEM }.forEach { m ->
                    add(buildJsonObject {
                        put("role", if (m.role == LlmChatRole.ASSISTANT) "assistant" else "user")
                        if (m.role == LlmChatRole.USER && m.attachments.isNotEmpty()) {
                            if (hasImageAttachments(m.attachments)) {
                                put("content", anthropicContent(m))
                            } else {
                                put("content", finalText(m))
                            }
                        } else {
                            put("content", m.content)
                        }
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
                        if (m.role == LlmChatRole.USER && m.attachments.isNotEmpty()) {
                            if (hasImageAttachments(m.attachments)) {
                                put("parts", googleParts(m))
                            } else {
                                put("parts", buildJsonArray { add(buildJsonObject { put("text", finalText(m)) }) })
                            }
                        } else {
                            put("parts", buildJsonArray { add(buildJsonObject { put("text", m.content) }) })
                        }
                    })
                }
            })
            put("generationConfig", buildJsonObject {
                a.extraParameters["top_k"]?.let { put("topK", it) }
                a.extraParameters["seed"]?.let { put("seed", it) }
                a.extraParameters["stop"]?.let { put("stopSequences", it) }
                if (!resolved.enabled) a.safeTemperature?.let { put("temperature", it) }
                if (a.sendMaxTokens) put("maxOutputTokens", baseMax)
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

    // ---- Вложения -----------------------------------------------------------
    // Изображения идут мультимодальными блоками (формат провайдера), текстовые файлы —
    // в текст сообщения, прочие бинарные файлы в чат не отправляются.
    // Исторические сообщения вложений не несут — по ним только текст.
    // Системные промпты — только текстом: провайдеры не принимают туда изображения.

    private fun imageAttachments(message: LlmMessage): List<Attachment> =
        message.attachments.filter { it.kind == AttachmentKind.IMAGE && it.dataBase64.isNotEmpty() }

    private fun textBlock(message: LlmMessage): String = buildString {
        if (message.content.isNotBlank()) {
            append(message.content)
        }
        message.attachments.filter { it.kind == AttachmentKind.TEXT }.forEach { att ->
            if (isNotEmpty()) append("\n\n")
            append("Файл ").append(att.name).append(":\n")
            append(att.decodeText())
        }
    }

    private fun binaryNotice(message: LlmMessage): String? =
        message.attachments
            .filter { it.kind == AttachmentKind.FILE }
            .map { it.name }
            .takeIf { it.isNotEmpty() }
            ?.let { "(Приложены файлы без передачи содержимого: ${it.joinToString()})" }

    private fun finalText(message: LlmMessage): String {
        val body = textBlock(message)
        val notice = binaryNotice(message)
        return when {
            notice == null -> body
            body.isBlank() -> notice
            else -> "$body\n\n$notice"
        }
    }

    private fun dataUrl(att: Attachment): String = "data:${att.mimeType};base64,${att.dataBase64}"

    /** Картинки требуют мультимодального массива; текст и файлы уходят строкой. */
    private fun hasImageAttachments(attachments: List<Attachment>): Boolean =
        attachments.any { it.kind == AttachmentKind.IMAGE && it.dataBase64.isNotEmpty() }

    private fun openAiContent(message: LlmMessage): kotlinx.serialization.json.JsonElement {
        val images = imageAttachments(message)
        val text = finalText(message)
        return buildJsonArray {
            if (text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", text) })
            images.forEach { att ->
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", dataUrl(att)) })
                })
            }
        }
    }

    // Anthropic: контент-массив с блоками «текст» и «изображение» (источник — base64).
    private fun anthropicContent(message: LlmMessage): kotlinx.serialization.json.JsonElement {
        val images = imageAttachments(message)
        val text = finalText(message)
        return buildJsonArray {
            if (text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", text) })
            images.forEach { att ->
                add(buildJsonObject {
                    put("type", "image")
                    put("source", buildJsonObject {
                        put("type", "base64")
                        put("media_type", att.mimeType)
                        put("data", att.dataBase64)
                    })
                })
            }
        }
    }

    // Google: parts — текст и «встроенные данные» (inlineData).
    private fun googleParts(message: LlmMessage): kotlinx.serialization.json.JsonArray {
        val images = imageAttachments(message)
        val text = finalText(message)
        return buildJsonArray {
            if (text.isNotBlank()) add(buildJsonObject { put("text", text) })
            images.forEach { att ->
                add(buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", att.mimeType)
                        put("data", att.dataBase64)
                    })
                })
            }
        }
    }
}
