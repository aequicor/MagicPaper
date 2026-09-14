package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.tools.*
import io.aequicor.magicpaper.util.Id
import kotlinx.serialization.json.JsonPrimitive

/** Runs only for an explicit retry. Resolves exact receipts before the organism's version-fenced commit. */
internal class PlanRetryNativeRecovery(private val runtime: CodingRuntime, private val receipts: ToolReceiptStore,
    private val knownSecrets: () -> Set<String> = { emptySet() }) {
    suspend fun reconcile(request: PlanRetryRecoveryRequest): PlanRetryRecoveryProof? {
        runtime.reconcile(request.session.id)
        val history = receipts.forOwner(request.session.projectId, request.session.id) ?: return null
        val unresolved = history.filter { it.mutating && it.phase !in terminal }
        val prefix = "${request.session.projectId}/${request.session.id}/${request.binding.attemptId}-turn-${request.binding.turnIndex}/native/"
        // A different turn/generation or a non-native side effect needs its own reconciler.
        if (unresolved.any { !it.native || it.runtimeGeneration != request.binding.generation || !it.id.startsWith(prefix) }) return null
        val ids = unresolved.associateBy { it.id.removePrefix(prefix).replace("%2F", "/").replace("%25", "%") }
        val results = runtime.nativeToolResults(request.session, ids.keys).groupBy { it.callId }
        for ((callId, old) in ids) {
            val result = results[callId]?.singleOrNull()?.takeIf { it.phase in terminal } ?: continue
            // Never replace a newer generation or a concurrently settled receipt.
            val current = receipts.get(old.id) ?: return null
            if (current != old) continue
            receipts.save(current.copy(phase = checkNotNull(result.phase), result = JsonPrimitive(result.resultPreview),
                error = if (result.isError) result.resultPreview else "", resultComplete = true, updatedAt = Id.now()).forPersistence(knownSecrets()))
        }
        val verified = receipts.forOwner(request.session.projectId, request.session.id) ?: return null
        if (verified.any { it.mutating && it.phase !in terminal }) return null
        val evidence = mutableListOf("Предыдущий процесс ${request.session.id} сверён с владельцем и остановлен")
        for (quarantine in request.quarantines) {
            val receipt = verified.singleOrNull { it.runtimeGeneration == request.binding.generation &&
                quarantine.operationId == "quarantine-${it.operationId.ifBlank { it.id }}" && it.phase in terminal } ?: return null
            evidence += "${receipt.id}: ${receipt.phase} (журнал инструмента)"
        }
        return PlanRetryRecoveryProof(request.quarantines.map { it.operationId }.toSet(), evidence)
    }

    private companion object { val terminal = setOf(ToolPhase.SUCCEEDED, ToolPhase.FAILED, ToolPhase.CANCELLED) }
}
