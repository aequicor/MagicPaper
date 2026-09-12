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
     * `"qwen"`, `"qwen-chat-template"`, `null` — схема по умолчанию (reasoning_effort).
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
         *  - Qwen: VL-ряд (`qwen-vl-*`, `qwen2-vl-*`, `qwen2.5-vl-*`),
         *    а также флагманские модели (`-max`, `-plus`, `-turbo`) — Qwen 3.5+
         *    флагманы являются мультимодальными (hybrid-thinking);
         *  - Claude 3+ (Haiku 3, Sonnet 3/4/5, Opus 3/4);
         *  - GPT-4 и новее (gpt-4*, gpt-5*, o-серия);
         *  - Gemini (все поколения мультимодальные);
         *  - GLM-4V и новее;
         *  - Grok с vision.
         */
        private fun resolveVision(provider: ProviderType, family: String, id: String): Boolean = when {
            // Qwen: VL-ряд и флагманские модели (Qwen 3.5+ multimodal)
            family == "qwen" && (
                id.contains("-vl") ||
                    id.endsWith("-max") || id.endsWith("-plus") || id.endsWith("-turbo") ||
                    id.contains("-max-") || id.contains("-plus-") || id.contains("-turbo-")
                ) -> true
            // Claude 3+ (haiku-3, sonnet-3/4/5, opus-3/4)
            family == "claude" && id.any { it.isDigit() && it >= '3' } -> true
            // GPT-4+, o-серия
            id.startsWith("gpt-4") || id.startsWith("gpt-5") ||
                id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4") -> true
            // Gemini (все поколения мультимодальные)
            family == "gemini" -> true
            // GLM-4V+
            family == "glm" && id.contains("v") -> true
            // Grok vision
            id.contains("grok") && id.contains("vision") -> true
            else -> false
        }

        // ---- Thinking format ---------------------------------------------------------

        /**
         * Диалект переключателей мышления для pi-агента.
         *
         * - Qwen на наружных эндпоинтах — `enable_thinking` (`"qwen"`)
         * - Qwen на локальных серверах — `chat_template_kwargs` (`"qwen-chat-template"`)
         * - Остальным — `null` (pi выберет `reasoning_effort` самостоятельно)
         */
        private fun resolveThinkingFormat(family: String, baseUrl: String): String? = when {
            family != "qwen" -> null
            isLocalEndpoint(baseUrl) -> "qwen-chat-template"
            else -> "qwen"
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
            ProviderType.ANTHROPIC, ProviderType.GOOGLE, ProviderType.OPENAI_SUBSCRIPTION -> false
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
    }
}
