package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.llm.LlmPayloads
import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.json.*

/** Host generation controls shared with core AI; native adapters receive only the resolved values. */
object ProviderControls {
    fun parameters(profile: LlmProfile): JsonObject {
        val capability = ModelDefaults.capability(profile)
        val body = when (profile.provider) {
            ProviderType.ANTHROPIC -> LlmPayloads.anthropic(profile, emptyList(), capability)
            ProviderType.GOOGLE -> return LlmPayloads.google(profile, emptyList(), capability)["generationConfig"]!!.jsonObject
            else -> LlmPayloads.openAi(profile, emptyList(), capability)
        }
        return JsonObject(body.filterKeys { it !in setOf("model", "messages", "stream", "system") })
    }

}
