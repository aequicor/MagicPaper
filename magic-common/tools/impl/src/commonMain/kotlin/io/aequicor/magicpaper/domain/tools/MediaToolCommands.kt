package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Owns media generation and terminal receipt settlement for the application's lifetime. */
class DefaultMediaToolCommands(
    private val service: MediaGenerationService?,
    private val allowed: suspend (ToolExecutionContext, MediaKind) -> Boolean,
    private val checkScope: suspend (ToolExecutionContext) -> Unit,
    private val authorizeTool: suspend (ToolExecutionContext, ToolDefinition) -> Unit,
    private val receiptOwner: MediaToolReceiptOwner,
) : MediaToolCommands {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    override val receiptLock get() = receiptOwner.lock
    override suspend fun prepareContext(context: ToolExecutionContext, policy: SessionMediaTools): ToolExecutionContext {
        val service = service ?: return context
        if (context.mode == CodingInteractionMode.PLANNING || context.auxiliaryExecution) return context
        val available = MediaKind.entries.filter { policy.enabled(it) && allowed(context, it) && service.available(it) }.toSet()
        return context.copy(mediaCapabilities = available)
    }
    override fun kind(id: String): MediaKind? = when (id) {
        "image.generate" -> MediaKind.IMAGE
        "video.generate" -> MediaKind.VIDEO
        else -> null
    }
    override suspend fun generate(context: ToolExecutionContext, operationId: String, tool: String,
        arguments: JsonObject, researchChat: Boolean): JsonElement {
        val kind = checkNotNull(kind(tool))
        if (!allowed(context, kind)) throw MediaToolUnavailable()
        val service = service ?: throw MediaToolUnavailable()
        val args = json.decodeFromJsonElement<MediaToolArgs>(arguments)
        requireTool(args.prompt.isNotBlank()) { "Опишите изображение или видео" }
        requireTool(args.width >= 0 && args.height >= 0 && args.durationSeconds >= 0) { "Параметры генерации не могут быть отрицательными" }
        val progress = currentCoroutineContext()[MediaToolProgress]
        val media = service.generate(MediaGenerationOwner(context.ownerSessionId, context.requestId, progress?.callId ?: operationId,
            projectId = context.projectId.takeUnless { researchChat }, runtimeGeneration = context.runtimeGeneration),
            operationId, MediaGenerationRequest(kind, args.prompt, args.caption, args.width, args.height, args.durationSeconds),
            authorize = {
                if (!allowed(context, kind)) throw MediaToolUnavailable()
                if (!researchChat) {
                    checkScope(context)
                    authorizeTool(context, ToolCatalog.get(tool))
                }
            },
            onUpdate = { progress?.publish?.invoke(it) })
        return mediaResult(media)
    }
    private suspend fun mediaResult(media: GeneratedMedia): JsonElement {
        val path = media.asset?.let { service?.localPath(it) }
        return buildJsonObject {
            put("media", json.encodeToJsonElement(media))
            path?.let { put("path", it) }
        }
    }
    override suspend fun reconcile(receipt: ToolReceipt): JsonElement? =
        if (kind(receipt.toolId) != null) service?.recover(receipt.operationId)?.let { mediaResult(it) } else null

    override suspend fun reconcileCompletion(owner: MediaGenerationOwner, operationId: String, media: GeneratedMedia) =
        receiptOwner.reconcileCompletion(owner, operationId, media)

}

private class MediaToolUnavailable : IllegalStateException("Инструмент создания медиа отключён или подключение недоступно"), RejectedToolCall

class DefaultMediaToolCommandsFactory : MediaToolCommands.Factory {
    override fun create(receipts: ToolReceiptStore, service: MediaGenerationService?,
        allowed: suspend (ToolExecutionContext, MediaKind) -> Boolean,
        checkScope: suspend (ToolExecutionContext) -> Unit,
        authorizeTool: suspend (ToolExecutionContext, ToolDefinition) -> Unit,
        receiptOwner: MediaToolReceiptOwner): MediaToolCommands =
        DefaultMediaToolCommands(service, allowed, checkScope, authorizeTool, receiptOwner)
}
