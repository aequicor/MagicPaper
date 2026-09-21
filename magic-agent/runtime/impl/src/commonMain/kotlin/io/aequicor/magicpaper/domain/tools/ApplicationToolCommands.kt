package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.json.*

/** Immutable command routing; generation, questionnaires and lifecycle remain with their owners. */
class ApplicationToolCommands(
    private val questionnaires: QuestionnaireToolCommands,
    private val media: MediaToolCommands,
    private val dispatch: OrchestrationActions,
    private val orchestration: CustomOrchestration?,
    private val taskHandoff: (suspend (ToolExecutionContext, TaskHandoff) -> Unit)?,
    private val search: (suspend (ToolExecutionContext, String) -> JsonElement)?,
) {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun project(context: ToolExecutionContext,
        overrides: Map<String, suspend (ToolExecutionContext, String, JsonObject) -> JsonElement>): List<JsonToolCommand> {
        return ToolCatalog.definitions.filter { !it.native && (it.id != "web.search" || search != null) &&
            (media.kind(it.id)?.let { kind -> kind in context.mediaCapabilities } != false) }.map { definition ->
            JsonToolCommand(definition) { ctx, id, args ->
                val override = overrides[definition.id]
                if (override != null) override(ctx, id, args)
                else when (definition.id) {
                    "questionnaire" -> questionnaires.ask(ctx, id, args)
                    "task.handoff" -> {
                        val handoff = json.decodeFromJsonElement<TaskHandoff>(args)
                        checkNotNull(taskHandoff) { "Worktree недоступен" }(ctx, handoff)
                        buildJsonObject {
                            put("accepted", true)
                            put("outcome", handoff.outcome.name)
                            put("nextAction", "finish_response")
                            put("message", if (handoff.outcome == TaskHandoffOutcome.RESULT)
                                "Результат принят. Заверши ответ: приложение дождётся остановки исполнителей, выполнит проверки и автоматическое слияние. Не открывай опросник для подтверждения слияния или паузы. Слияние ещё не подтверждено."
                            else "Блокировка сохранена, рабочая копия сохранена. Сообщи причину блокировки и заверши ответ; автоматическое слияние не разрешено.")
                        }
                    }
                    "web.search" -> search!!(ctx, json.decodeFromJsonElement<ToolSearch>(args).query.also { require(it.isNotBlank()) })
                    "image.generate", "video.generate" -> media.generate(ctx, id, definition.id, args)
                    else -> if (definition.orchestration && orchestration != null) orchestration.execute(ctx, id, definition.id, args)
                        else dispatch.execute(ctx, id, definition.id, args)
                }
            }
        }
    }

    fun research(context: ToolExecutionContext, allowSearch: Boolean): List<JsonToolCommand> {
        return buildList {
            search?.takeIf { allowSearch }?.let { find -> add(JsonToolCommand(ToolCatalog.get("web.search")) { ctx, _, args ->
                find(ctx, json.decodeFromJsonElement<ToolSearch>(args).query.also { require(it.isNotBlank()) })
            }) }
            add(JsonToolCommand(ToolCatalog.get("questionnaire")) { ctx, id, args -> questionnaires.ask(ctx, id, args) })
            ToolCatalog.definitions.filter { media.kind(it.id)?.let { kind -> kind in context.mediaCapabilities } == true }.forEach { definition ->
                add(JsonToolCommand(definition) { ctx, id, args -> media.generate(ctx, id, definition.id, args, researchChat = true) })
            }
        }
    }
}
