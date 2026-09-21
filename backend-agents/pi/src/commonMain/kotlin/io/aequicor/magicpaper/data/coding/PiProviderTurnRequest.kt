package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.*

/** A provider request only: this process never receives a project, native tools or agent instructions. */
data class PiProviderTurnRequest(
    val profile: LlmProfile,
    val messages: List<LlmMessage>,
    val tools: List<LlmToolDefinition>,
    val exchanges: List<LlmToolExchange>,
    val nodePath: String,
    val libraryPath: String,
    val scriptPath: String,
    val accessToken: String,
) {
    override fun toString(): String = "PiProviderTurnRequest(provider=${profile.provider}, model=${profile.modelId}, " +
        "messages=${messages.size}, tools=${tools.size}, exchanges=${exchanges.size})"
}
