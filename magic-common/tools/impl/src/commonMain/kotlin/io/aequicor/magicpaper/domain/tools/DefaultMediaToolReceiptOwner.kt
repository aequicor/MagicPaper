package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Terminal generation evidence outlives whichever native/provider awaiter started it. */
class DefaultMediaToolReceiptOwner(
    private val receipts: ToolReceiptStore,
    private val resolvePath: suspend (MediaAsset) -> String?,
) : MediaToolReceiptOwner {
    private val json = Json { encodeDefaults = true }
    override val lock = Mutex()
    override suspend fun reconcileCompletion(owner: MediaGenerationOwner, operationId: String, media: GeneratedMedia) = lock.withLock {
        val receipt = receipts.get(owner.callId) ?: return@withLock
        val kind = when (receipt.toolId) { "image.generate" -> MediaKind.IMAGE; "video.generate" -> MediaKind.VIDEO; else -> null }
        require(receipt.operationId == operationId && receipt.runtimeGeneration == owner.runtimeGeneration &&
            kind == media.kind && media.id == owner.callId) { "Идентификатор генерации изменился" }
        if (receipt.phase == ToolPhase.SUCCEEDED) return@withLock
        val phase = when (media.phase) {
            MediaPhase.READY -> ToolPhase.SUCCEEDED
            MediaPhase.FAILED -> ToolPhase.FAILED
            else -> return@withLock
        }
        val result = if (phase == ToolPhase.SUCCEEDED) {
            val path = media.asset?.let { resolvePath(it) }
            buildJsonObject { put("media", json.encodeToJsonElement(media)); path?.let { put("path", it) } }
        } else receipt.result
        receipts.save(receipt.copy(phase = phase, result = result,
            error = if (phase == ToolPhase.SUCCEEDED) "" else media.message, updatedAt = Id.now()))
    }
}
