package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.tools.*
import io.aequicor.magicpaper.util.Id
import kotlinx.serialization.json.JsonPrimitive

/**
 * Resolves unknown side effects from the engine's own history. Only an exact terminal engine
 * item settles a receipt; a missing item leaves it unknown, and an unresolved effect outside
 * this scope (another generation or a non-native call) makes the whole recovery unavailable.
 */
internal class NativeReceiptRecovery(
    private val runtime: CodingRuntime,
    private val receipts: ToolReceiptStore,
    private val knownSecrets: () -> Set<String> = { emptySet() },
) {
    /** Returns the reconciled receipt history, or null when the outcome stays unproven. */
    suspend fun resolve(session: CodingSession, generation: Long, requestPrefix: String? = null): List<ToolReceipt>? {
        runtime.reconcile(session.id)
        val history = receipts.forOwner(session.projectId, session.id) ?: return null
        val unresolved = history.filter { it.mutating && it.phase !in terminal }
        if (unresolved.any { !it.native || it.runtimeGeneration != generation ||
                (requestPrefix != null && !it.id.startsWith(requestPrefix)) }) return null
        val ids = unresolved.associateBy { it.callId(requestPrefix) }
        val results = runtime.nativeToolResults(session, ids.keys).groupBy { it.callId }
        for ((callId, old) in ids) {
            val result = results[callId]?.singleOrNull()?.takeIf { it.phase in terminal } ?: continue
            // Never replace a newer generation or a concurrently settled receipt.
            val current = receipts.get(old.id) ?: return null
            if (current != old) continue
            receipts.save(current.copy(phase = checkNotNull(result.phase), result = JsonPrimitive(result.resultPreview),
                error = if (result.isError) result.resultPreview else "", resultComplete = true, updatedAt = Id.now()).forPersistence(knownSecrets()))
        }
        val verified = receipts.forOwner(session.projectId, session.id) ?: return null
        if (verified.any { it.mutating && it.phase !in terminal }) return null
        return verified
    }

    private fun ToolReceipt.callId(requestPrefix: String?): String =
        (requestPrefix?.let { id.removePrefix(it) } ?: id.substringAfterLast("$NATIVE_SEGMENT/"))
            .replace("%2F", "/").replace("%25", "%")

    companion object {
        val terminal: Set<ToolPhase> = setOf(ToolPhase.SUCCEEDED, ToolPhase.FAILED, ToolPhase.CANCELLED)
        private const val NATIVE_SEGMENT = "native"
    }
}
