package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.json.JsonObject

internal fun piModelOptions(profile: LlmProfile, parameters: JsonObject): String {
        if (profile.provider == ProviderType.OPENAI_SUBSCRIPTION) return "export default function() {}"
        val google = profile.provider == ProviderType.GOOGLE
        return """
            export default function(pi) {
              const parameters = $parameters;
              const providerDefault = ${profile.effortSelectionFor().isDefault};
              pi.on("before_provider_request", (event) => {
                const payload = { ...event.payload };
                const controls = $google ? { ...payload.config } : payload;
                for (const key of ["temperature", "top_p", "topP", "max_tokens", "max_completion_tokens", "maxOutputTokens",
                  "reasoning_effort", "reasoning", "thinking", "thinkingConfig", "output_config", "thinking_budget", "thinkingBudget"]) delete controls[key];
                if (providerDefault) { delete controls.enable_thinking; delete controls.disable_reasoning; }
                if (providerDefault && controls.chat_template_kwargs) {
                  controls.chat_template_kwargs = { ...controls.chat_template_kwargs };
                  delete controls.chat_template_kwargs.enable_thinking; delete controls.chat_template_kwargs.thinking_budget;
                }
                Object.assign(controls, parameters);
                if ($google) payload.config = controls;
                return payload;
              });
            }
        """.trimIndent()
    }
