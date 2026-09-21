package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.util.Id
import kotlinx.serialization.json.*

/** Parsing and delivery belong to the persisted questionnaire service, never a runtime screen. */
class DefaultQuestionnaireToolCommands(override val questions: RuntimeQuestionnaireService) : QuestionnaireToolCommands {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    override suspend fun ask(ctx: ToolExecutionContext, id: String, args: JsonObject): JsonElement {
        val request = json.decodeFromJsonElement<ToolQuestions>(args)
        val safe = QuestionnaireContract.ready(request.questions)
        val answers = questions.ask(UserInteractionRequest("tool:$id", ctx.projectId, ctx.ownerSessionId,
            InteractionKind.RUNTIME, safe, ownerSessionId = ctx.ownerSessionId, createdAt = Id.now(),
            runtimeGeneration = ctx.runtimeGeneration, runId = ctx.runId ?: ctx.requestId))
        return json.encodeToJsonElement(answers)
    }

}

class DefaultQuestionnaireToolCommandsFactory : QuestionnaireToolCommands.Factory {
    override fun create(questions: RuntimeQuestionnaireService): QuestionnaireToolCommands = DefaultQuestionnaireToolCommands(questions)
}
