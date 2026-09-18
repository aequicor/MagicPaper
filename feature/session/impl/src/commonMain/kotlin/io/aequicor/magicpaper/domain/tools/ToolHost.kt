package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Composition root binds receivers; every invocation gets a fresh, application-owned scope. */
class ToolHost(val receipts: ToolReceiptStore, val questions: RuntimeQuestionnaires = RuntimeQuestionnaires()) {
    var contextDefaults: (ToolExecutionContext) -> ToolExecutionContext = { it }
    var knownSecrets: () -> Set<String> = { emptySet() }
    var checkScope: suspend (ToolExecutionContext) -> Unit = {}
    var checkReplayScope: suspend (ToolExecutionContext) -> Unit = { checkScope(it) }
    var reconcile: suspend (ToolExecutionContext, ToolReceipt) -> JsonElement? = { _, _ -> null }
    var unknownOutcome: suspend (ToolExecutionContext, ToolReceipt) -> Unit = { _, _ -> }
    var authorizeTool: suspend (ToolExecutionContext, ToolDefinition) -> Unit = { _, _ -> }
    var authorizeCommand: suspend (ToolExecutionContext, ToolDefinition, JsonObject) -> Unit = { _, _, _ -> }
    var authorizeReceipt: suspend (ToolExecutionContext, ToolDefinition) -> Unit = { context, definition -> authorizeTool(context, definition) }
    var orchestration: CustomOrchestration? = null
    var receiver: suspend (ToolExecutionContext, String, String, JsonObject) -> JsonElement = { _, _, _, _ -> error("Инструменты приложения не подключены") }
    var prepareWorker: suspend (CodingSession) -> ToolExecutionContext = { ToolExecutionContext.worker(it) }
    var search: (suspend (ToolExecutionContext, String) -> JsonElement)? = null
    var taskHandoff: (suspend (ToolExecutionContext, TaskHandoff) -> Unit)? = null
    var mediaGeneration: MediaGenerationService? = null
    /** Re-read saved policy at the side-effect boundary, including a toggle changed mid-run. */
    var mediaAllowed: suspend (ToolExecutionContext, MediaKind) -> Boolean = { _, _ -> true }
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val mediaReceiptLock = Mutex()
    suspend fun prepareMediaContext(context: ToolExecutionContext, policy: SessionMediaTools): ToolExecutionContext {
        val service = mediaGeneration ?: return context
        if (context.mode == CodingInteractionMode.PLANNING || context.auxiliaryExecution) return context
        val available = MediaKind.entries.filter { policy.enabled(it) && mediaAllowed(context, it) && service.available(it) }.toSet()
        return context.copy(mediaCapabilities = available)
    }
    private fun mediaKind(id: String): MediaKind? = when (id) {
        "image.generate" -> MediaKind.IMAGE
        "video.generate" -> MediaKind.VIDEO
        else -> null
    }
    private suspend fun generateMedia(context: ToolExecutionContext, operationId: String, tool: String,
        arguments: JsonObject, researchChat: Boolean = false): JsonElement {
        val kind = checkNotNull(mediaKind(tool))
        if (!mediaAllowed(context, kind)) throw MediaToolUnavailable()
        val service = mediaGeneration ?: throw MediaToolUnavailable()
        val args = json.decodeFromJsonElement<MediaToolArgs>(arguments)
        requireTool(args.prompt.isNotBlank()) { "Опишите изображение или видео" }
        requireTool(args.width >= 0 && args.height >= 0 && args.durationSeconds >= 0) { "Параметры генерации не могут быть отрицательными" }
        val progress = currentCoroutineContext()[MediaToolProgress]
        val media = service.generate(MediaGenerationOwner(context.ownerSessionId, context.requestId, progress?.callId ?: operationId,
            projectId = context.projectId.takeUnless { researchChat }, runtimeGeneration = context.runtimeGeneration),
            operationId, MediaGenerationRequest(kind, args.prompt, args.caption, args.width, args.height, args.durationSeconds),
            authorize = {
                if (!mediaAllowed(context, kind)) throw MediaToolUnavailable()
                if (!researchChat) {
                    checkScope(context)
                    authorizeTool(context, ToolCatalog.get(tool))
                }
            },
            onUpdate = { progress?.publish?.invoke(it) })
        return mediaResult(media)
    }
    private suspend fun mediaResult(media: GeneratedMedia): JsonElement {
        val path = media.asset?.let { mediaGeneration?.localPath(it) }
        return buildJsonObject {
            put("media", json.encodeToJsonElement(media))
            path?.let { put("path", it) }
        }
    }
    private suspend fun reconcileMedia(context: ToolExecutionContext, receipt: ToolReceipt): JsonElement? =
        if (mediaKind(receipt.toolId) != null) mediaGeneration?.recover(receipt.operationId)?.let { mediaResult(it) }
        else reconcile(context, receipt)
    /** Settle only the saved media effect. Existing quarantine recovery still owns process reconciliation. */
    suspend fun reconcileMediaCompletion(owner: MediaGenerationOwner, operationId: String, media: GeneratedMedia) = mediaReceiptLock.withLock {
        val receipt = receipts.get(owner.callId) ?: return@withLock
        require(receipt.operationId == operationId && receipt.runtimeGeneration == owner.runtimeGeneration &&
            mediaKind(receipt.toolId) == media.kind && media.id == owner.callId) { "Идентификатор генерации изменился" }
        if (receipt.phase == ToolPhase.SUCCEEDED) return@withLock
        val phase = when (media.phase) {
            MediaPhase.READY -> ToolPhase.SUCCEEDED
            MediaPhase.FAILED -> ToolPhase.FAILED
            else -> return@withLock
        }
        receipts.save(receipt.copy(phase = phase, result = if (phase == ToolPhase.SUCCEEDED) mediaResult(media) else receipt.result,
            error = if (phase == ToolPhase.SUCCEEDED) "" else media.message, updatedAt = Id.now()))
    }
    internal suspend fun askQuestionnaire(ctx: ToolExecutionContext, id: String, args: JsonObject): JsonElement {
        val request = json.decodeFromJsonElement<ToolQuestions>(args)
        val safe = QuestionnaireContract.ready(request.questions)
        val answers = questions.ask(UserInteractionRequest("tool:$id", ctx.projectId, ctx.ownerSessionId,
            InteractionKind.RUNTIME, safe, ownerSessionId = ctx.ownerSessionId, createdAt = Id.now(),
            runtimeGeneration = ctx.runtimeGeneration, runId = ctx.runId ?: ctx.requestId))
        return json.encodeToJsonElement(answers)
    }

    /** Research chats are owned by ChatService, not the project/plan registry.
     * Only explicitly available research tools cross this boundary; cancellation revokes the run. */
    internal fun researchChatSession(context: ToolExecutionContext, allowSearch: Boolean = true): ToolSession {
        require(context.role == ToolRole.CHAT && context.mode == CodingInteractionMode.RESEARCH && context.planId == null)
        val commands = buildList {
            search?.takeIf { allowSearch }?.let { find -> add(JsonToolCommand(ToolCatalog.get("web.search")) { ctx, _, args ->
                find(ctx, json.decodeFromJsonElement<ToolSearch>(args).query.also { require(it.isNotBlank()) })
            }) }
            add(JsonToolCommand(ToolCatalog.get("questionnaire")) { ctx, id, args -> askQuestionnaire(ctx, id, args) })
            ToolCatalog.definitions.filter { mediaKind(it.id)?.let { kind -> kind in context.mediaCapabilities } == true }.forEach { definition ->
                add(JsonToolCommand(definition) { ctx, id, args -> generateMedia(ctx, id, definition.id, args, researchChat = true) })
            }
        }
        val registry = ToolRegistry(commands)
        return ToolSession(context, registry, ToolExecutor(registry, receipts,
            checkScope = { currentCoroutineContext().ensureActive() }, reconcile = { ctx, receipt -> reconcileMedia(ctx, receipt) },
            knownSecrets = { knownSecrets() }, mediaReceiptLock = mediaReceiptLock), knownSecrets = { knownSecrets() })
    }

    fun session(context: ToolExecutionContext, overrides: Map<String, suspend (ToolExecutionContext, String, JsonObject) -> JsonElement> = emptyMap()): ToolSession {
        val scopedContext = contextDefaults(context)
        val commands = ToolCatalog.definitions.filter { !it.native && (it.id != "web.search" || search != null) &&
            (mediaKind(it.id)?.let { kind -> kind in scopedContext.mediaCapabilities } != false) }.map { definition ->
            JsonToolCommand(definition) { ctx, id, args ->
                val override = overrides[definition.id]
                if (override != null) override(ctx, id, args)
                else when (definition.id) {
                    "questionnaire" -> askQuestionnaire(ctx, id, args)
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
                    "image.generate", "video.generate" -> generateMedia(ctx, id, definition.id, args)
                    else -> if (definition.orchestration && orchestration != null) orchestration!!.execute(ctx, id, definition.id, args)
                        else receiver(ctx, id, definition.id, args)
                }
            }
        }
        val registry = ToolRegistry(commands)
        val executionContext = if (scopedContext.role in setOf(ToolRole.ORCHESTRATOR, ToolRole.PLANNER) && scopedContext.sessionId == scopedContext.ownerSessionId)
            scopedContext.copy(sessionId = "planning-${scopedContext.requestId}-${Id.new()}") else scopedContext
        return ToolSession(executionContext, registry, ToolExecutor(registry, receipts, checkScope, checkReplayScope, ::reconcileMedia,
            knownSecrets = { knownSecrets() }, recoverQuestionnaire = { ctx, receipt ->
                askQuestionnaire(ctx, receipt.operationId, receipt.arguments)
            }, unknownOutcome = { ctx, receipt -> unknownOutcome(ctx, receipt) },
            authorizeTool = { ctx, definition -> authorizeTool(ctx, definition) },
            authorizeCommand = { ctx, definition, args -> authorizeCommand(ctx, definition, args) },
            authorizeReceipt = { ctx, definition -> authorizeReceipt(ctx, definition) }, mediaReceiptLock = mediaReceiptLock), knownSecrets = { knownSecrets() })
    }
}

private class MediaToolUnavailable : IllegalStateException("Инструмент создания медиа отключён или подключение недоступно"), RejectedToolCall
