package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.llm.LlmPayloads
import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.json.*

/** The same generation controls as chat, applied after Pi serializes tools and messages. */
object PiModelOptions {
    fun parameters(profile: LlmProfile): JsonObject {
        val capability = ModelDefaults.capability(profile)
        val body = when (profile.provider) {
            ProviderType.ANTHROPIC -> LlmPayloads.anthropic(profile, emptyList(), capability)
            ProviderType.GOOGLE -> return LlmPayloads.google(profile, emptyList(), capability)["generationConfig"]!!.jsonObject
            else -> LlmPayloads.openAi(profile, emptyList(), capability)
        }
        return JsonObject(body.filterKeys { it !in setOf("model", "messages", "stream", "system") })
    }

    fun extension(profile: LlmProfile): String {
        if (profile.provider == ProviderType.OPENAI_SUBSCRIPTION) return "export default function() {}"
        val parameters = parameters(profile)
        val google = profile.provider == ProviderType.GOOGLE
        return """
            export default function(pi) {
              const parameters = $parameters;
              const providerDefault = ${profile.effortSelectionFor().isDefault};
              pi.on("before_provider_request", (event) => {
                const payload = { ...event.payload };
                const controls = $google ? { ...payload.config } : payload;
                for (const key of ["temperature", "top_p", "topP", "max_tokens", "max_completion_tokens", "maxOutputTokens",
                  "reasoning_effort", "reasoning", "thinking", "thinkingConfig", "output_config", "thinking_budget",
                  "thinkingBudget"]) delete controls[key];
                if (providerDefault) { delete controls.enable_thinking; delete controls.disable_reasoning; }
                if (providerDefault && controls.chat_template_kwargs) {
                  controls.chat_template_kwargs = { ...controls.chat_template_kwargs };
                  delete controls.chat_template_kwargs.enable_thinking;
                  delete controls.chat_template_kwargs.thinking_budget;
                }
                Object.assign(controls, parameters);
                if ($google) payload.config = controls;
                return payload;
              });
            }
        """.trimIndent()
    }
}
