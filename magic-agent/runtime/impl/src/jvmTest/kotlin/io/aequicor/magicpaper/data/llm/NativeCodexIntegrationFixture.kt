package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.backend.CodexClient
import io.aequicor.magicpaper.data.coding.backendProtocols
import io.aequicor.magicpaper.domain.CodingStep
import io.aequicor.magicpaper.domain.RuntimeQuestionnaireService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** These tests combine real runtime receipts/transcripts with protocol notifications.
 * Keep the existing reflection fixture local to tests; production sees only the backend API. */
internal fun CodexAppServerOpenAiSubscription.nativeForTest(): CodexClient =
    javaClass.getDeclaredField("native").apply { isAccessible = true }.get(this) as CodexClient

internal fun nativeAccumulatorType(client: CodexClient): Class<*> =
    client.javaClass.declaredClasses.single { it.simpleName == "CodingAccumulator" }

internal fun nativeTurnAccumulator(client: CodexClient, onActivity: (CodingStep) -> Unit): Any =
    client.javaClass.declaredClasses.single { it.simpleName == "TurnAccumulator" }
        .getDeclaredConstructor(Function1::class.java).apply { isAccessible = true }.newInstance(onActivity)

internal fun CodexClient.handleServerRequest(id: JsonPrimitive, method: String, params: JsonObject): Boolean =
    javaClass.declaredMethods.single { it.name.startsWith("handleServerRequest") }
        .apply { isAccessible = true }.invoke(this, id, method, params) as Boolean

internal fun CodexQuestionnaireBroker(scope: CoroutineScope, service: RuntimeQuestionnaireService,
    notice: (String, String) -> Unit, failed: (String, Throwable) -> Unit) =
    backendProtocols.codex.questionnaireBroker(scope, service.asNativeQuestionnaires(), notice, failed)


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
