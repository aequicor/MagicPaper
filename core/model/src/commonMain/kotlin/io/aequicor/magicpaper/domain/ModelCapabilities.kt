package io.aequicor.magicpaper.domain

/**
 * Единое описание возможностей модели — источник правды для транспортов,
 * кодинг-агента и UI. Заменяет разбросанные эвристики по имени (vision в
 * `PiModelsConfig.supportsImageInput`, thinking format в `PiModelsConfig.thinkingFormat`,
 * compat-флаги в `PiModelsConfig` и `ModelDefaults`).
 *
 * Получение: [resolve] — одна точка входа; потребители не гадают по имени сами.
 *
 * Провайдер учитывается: одна и та же модель на разных эндпоинтах может иметь
 * разные возможности (например, Qwen на DashScope vs. локальный сервер).
 */
data class ModelCapabilities(
    /** Модель принимает изображения (vision / multimodal) в user-сообщениях. */
    val vision: Boolean = false,

    /** Возможность управления рассуждением: уровни, диалект, бюджет. */
    val reasoning: ReasoningCapability = ReasoningCapability.None,

    /**
     * Диалект переключателей мышления для pi-агента (`thinkingFormat` в models.json):
     * `"qwen"`, `"qwen-chat-template"`, `"zai"`; `null` — схема по умолчанию (reasoning_effort).
     */
    val thinkingFormat: String? = null,

    /**
     * OpenAI-совместимый сервер требует промежуточное `role: "assistant"` между
     * `toolResult` и следующим `user`-сообщением. Без этого история ломается:
     * сервер возвращает 400 «Unexpected item type in content» (DashScope, Zhipu и др.).
     *
     * Anthropic и Google имеют собственные форматы сообщений и флага не требуют.
     */
    val requiresAssistantAfterToolResult: Boolean = false,
) {
    /** Есть ли у модели ручка усилия. */
    val supportsEffort: Boolean get() = reasoning.supportsEffort

    companion object {

        /**
         * Диалекты переключателей мышления: те же строки pi получает в `compat.thinkingFormat`.
         * Названы здесь, чтобы формат не разъехался между транспортом приложения и кодинг-агентом.
         */
        const val THINKING_QWEN = "qwen"
        const val THINKING_QWEN_CHAT_TEMPLATE = "qwen-chat-template"
        const val THINKING_ZAI = "zai"

        /**
         * Вычислить capabilities по провайдеру, идентификатору модели и (опционально)
         * base URL. Одна точка входа — все потребители вызывают [resolve].
         *
         * @param declared — объявления из каталога провайдера (`/models` API);
         *   перевешивают эвристику по имени, потому что это факт.
         */
        fun resolve(
            provider: ProviderType,
            modelId: String,
            baseUrl: String = "",
            declared: DeclaredReasoning? = null,
        ): ModelCapabilities {
            val id = modelId.trim().lowercase()
            if (id.isEmpty()) return ModelCapabilities()

            val reasoning = ModelDefaults.capability(provider, modelId, declared)
            val family = ProviderCatalog.familyOf(id)

            val vision = resolveVision(provider, family, id)
            val thinking = resolveThinkingFormat(family, baseUrl)
            val compatAssistant = resolveRequiresAssistantAfterToolResult(provider, vision)

            return ModelCapabilities(
                vision = vision,
                reasoning = reasoning,
                thinkingFormat = thinking,
                requiresAssistantAfterToolResult = compatAssistant,
            )
        }

        // ---- Vision ------------------------------------------------------------------

        /**
         * Модель принимает изображения — эвристика по id и провайдеру.
         *
         * Покрываемые семейства:
         *  - Qwen: VL-ряд, мультимодальный снимок `qwen3.7-max-2026-06-08`,
         *    Plus/Flash и новые мультимодальные Max;
         *  - Claude 3+ (Haiku 3, Sonnet 3/4/5, Opus 3/4);
         *  - GPT-4 и новее (gpt-4*, gpt-5*, o-серия);
         *  - Gemini (все поколения мультимодальные);
         *  - GLM-4V и новее, а также GLM-5.3-Flash (без «v» в имени);
         *  - Grok с vision.
         */
        private fun resolveVision(provider: ProviderType, family: String, id: String): Boolean = when {
            family == "qwen" && id.contains("-vl") -> true
            family == "qwen" && isQwenMultimodal(id) -> true
            // Claude 3+ (haiku-3, sonnet-3/4/5, opus-3/4)
            family == "claude" && id.any { it.isDigit() && it >= '3' } -> true
            // GPT-4+, o-серия
            id.startsWith("gpt-4") || id.startsWith("gpt-5") ||
                id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4") -> true
            // Gemini (все поколения мультимодальные)
            family == "gemini" -> true
            // GLM-4V+ и единственный мультимодальный GLM-5 без «v» в имени
            family == "glm" && (id.contains("v") || isGlmFlashMultimodal(id)) -> true
            // Grok vision
            id.contains("grok") && id.contains("vision") -> true
            else -> false
        }

        /**
         * The qwen3.7-max alias still resolves to the text-only May 20 snapshot,
         * including on Token Plan. Only the June 8 Max snapshot accepts images.
         * Do not infer a snapshot's modalities from another member of its family.
         * https://www.alibabacloud.com/help/en/model-studio/qwen3-7-max
         * https://www.alibabacloud.com/help/en/model-studio/token-plan-personal-overview
         */
        private fun isQwenMultimodal(id: String): Boolean {
            if (id == "qwen3.7-max" || id.startsWith("qwen3.7-max-")) {
                return id == "qwen3.7-max-2026-06-08"
            }
            if (!id.startsWith("qwen3.") || id.startsWith("qwen3-vl") || id.startsWith("qwen3.0")) return false
            if (id.contains("-preview") || id.contains("2026-05-20")) return false
            return id.contains("-max") || id.contains("-plus") || id.contains("-flash")
        }

        /**
         * GLM-5.3-Flash — «the first native multimodal model in the GLM-5 series»
         * (docs.z.ai/guides/llm/glm-5.3-flash: вход Video / Image / Text / File, блок
         * `type: image_url`). Тот же договор у каталога pi (`input: text, image`).
         * GLM-5.3 и остальные ряды GLM-5 принимают только текст, поэтому правило
         * не расползается на всё семейство: «v» в имени по-прежнему основной признак.
         */
        private fun isGlmFlashMultimodal(id: String): Boolean =
            id.substringAfterLast('/').startsWith("glm-5.3-flash")

        // ---- Thinking format ---------------------------------------------------------

        /**
         * Диалект переключателей мышления для pi-агента.
         *
         * - Qwen на наружных эндпоинтах — `enable_thinking` (`"qwen"`)
         * - Qwen на локальных серверах — `chat_template_kwargs` (`"qwen-chat-template"`)
         * - GLM на сервере Z.AI — `thinking.type` (`"zai"`); в маршруте агрегатора
         *   (OpenRouter и т.п.) этой формы нет — там усилием рулит `reasoning.effort`
         * - Остальным — `null` (pi выберет `reasoning_effort` самостоятельно)
         */
        private fun resolveThinkingFormat(family: String, baseUrl: String): String? = when {
            family == "glm" && isZaiEndpoint(baseUrl) -> THINKING_ZAI
            family != "qwen" -> null
            isLocalEndpoint(baseUrl) -> THINKING_QWEN_CHAT_TEMPLATE
            else -> THINKING_QWEN
        }

        // ---- Compat flags -------------------------------------------------------------

        /**
         * Промежуточный `assistant` между `toolResult` и `user`-сообщением.
         * Требуется OpenAI-совместимым серверам при отправке изображений:
         * pi-agent извлекает `image_url` из toolResult и отправляет отдельным
         * `user`-сообщением — без промежуточного assistant это ломает историю.
         *
         * Anthropic и Google имеют собственные форматы сообщений.
         */
        private fun resolveRequiresAssistantAfterToolResult(
            provider: ProviderType,
            vision: Boolean,
        ): Boolean = when (provider) {
            ProviderType.ANTHROPIC, ProviderType.GOOGLE, ProviderType.OPENAI_SUBSCRIPTION, ProviderType.ANTHROPIC_SUBSCRIPTION -> false
            else -> vision
        }

        // ---- Helpers ------------------------------------------------------------------

        /** Локальный сервер (Ollama, LM Studio, llama.cpp) — по имени хоста. */
        private fun isLocalEndpoint(baseUrl: String): Boolean {
            if (baseUrl.isBlank()) return false
            val host = baseUrl.substringAfter("://", baseUrl)
                .substringBefore('/')
                .substringBeforeLast(':')
                .trim('[', ']')
                .lowercase()
            return host == "localhost" || host == "::1" || host == "0.0.0.0" ||
                host == "host.docker.internal" ||
                host.startsWith("127.") || host.startsWith("192.168.") || host.startsWith("10.")
        }

        /**
         * Собственный сервер Z.AI (глобальный или китайский open.bigmodel.cn) —
         * только там GLM принимает поле `thinking`. Те же хосты распознаёт pi.
         */
        private fun isZaiEndpoint(baseUrl: String): Boolean {
            val url = baseUrl.lowercase()
            return "api.z.ai" in url || "open.bigmodel.cn" in url
        }
    }
}
