package io.aequicor.magicpaper.data.llm

import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject

/** Explicit local model transport fixture; production resolves connections through the provider library. */
internal fun CodexAppServerOpenAiSubscription.runFixtureCoding(
    project: io.aequicor.magicpaper.domain.CodingProject,
    session: io.aequicor.magicpaper.domain.CodingSession,
    prompt: String, profile: io.aequicor.magicpaper.domain.LlmProfile,
    attachments: List<io.aequicor.magicpaper.domain.Attachment>,
    providerId: String, configuration: JsonObject, planning: Boolean = false,
): kotlinx.coroutines.flow.Flow<io.aequicor.magicpaper.domain.CodingEvent> = kotlinx.coroutines.flow.channelFlow {
    val requestId = java.util.UUID.randomUUID().toString()
    val endpoint = configuration.getValue("model_providers.$providerId").jsonObject.getValue("base_url").jsonPrimitive.content
    val mode = if (planning) io.aequicor.magicpaper.domain.CodingInteractionMode.PLANNING
        else if (session.researchMode) io.aequicor.magicpaper.domain.CodingInteractionMode.RESEARCH
        else io.aequicor.magicpaper.domain.CodingInteractionMode.CODE
    io.aequicor.magicpaper.data.coding.AgentRunResources.prepare(project, session, planning, computerUse,
        questionnaireRegistry, browser, checks, kotlinx.coroutines.currentCoroutineContext()[io.aequicor.magicpaper.domain.tools.ToolSession],
        requestId = requestId).use { resources ->
        val agent = (binding.runtime as io.aequicor.magicpaper.data.coding.GenericNativeRuntime).agent
        agent.run(io.aequicor.magicpaper.backend.NativeAgentRequest(requestId, session, project.path, prompt,
            io.aequicor.magicpaper.data.coding.codingSystemPrompt(agent.descriptor.engine, planning, profile.advanced.systemPromptOverride, session = session),
            if (planning) io.aequicor.magicpaper.domain.PLANNING_INSTRUCTIONS else null, profile,
            kotlinx.serialization.json.JsonObject(emptyMap()), attachments, mode, resources.nativeTools(),
            io.aequicor.magicpaper.backend.NativeModelConnection(providerId, endpoint, ""))).collect { send(it) }
    }
}
