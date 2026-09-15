package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.tools.*

/** Runs only for an explicit retry. Resolves exact receipts before the organism's version-fenced commit. */
internal class PlanRetryNativeRecovery(private val recovery: NativeReceiptRecovery) {
    constructor(runtime: CodingRuntime, receipts: ToolReceiptStore, knownSecrets: () -> Set<String> = { emptySet() }) :
        this(NativeReceiptRecovery(runtime, receipts, knownSecrets))

    suspend fun reconcile(request: PlanRetryRecoveryRequest): PlanRetryRecoveryProof? {
        val prefix = "${request.session.projectId}/${request.session.id}/${request.binding.attemptId}-turn-${request.binding.turnIndex}/native/"
        // A different turn/generation or a non-native side effect needs its own reconciler.
        val verified = recovery.resolve(request.session, request.binding.generation, prefix) ?: return null
        val evidence = mutableListOf("Предыдущий процесс ${request.session.id} сверён с владельцем и остановлен")
        for (quarantine in request.quarantines) {
            val receipt = verified.singleOrNull { it.runtimeGeneration == request.binding.generation &&
                quarantine.operationId == "quarantine-${it.operationId.ifBlank { it.id }}" &&
                it.phase in NativeReceiptRecovery.terminal } ?: return null
            evidence += "${receipt.id}: ${receipt.phase} (журнал инструмента)"
        }
        return PlanRetryRecoveryProof(request.quarantines.map { it.operationId }.toSet(), evidence)
    }
}

/** Runs only for an explicit recovery of a quarantined session; it never re-executes the unknown call. */
internal class SessionQuarantineRecovery(private val recovery: NativeReceiptRecovery) {
    constructor(runtime: CodingRuntime, receipts: ToolReceiptStore, knownSecrets: () -> Set<String> = { emptySet() }) :
        this(NativeReceiptRecovery(runtime, receipts, knownSecrets))

    suspend fun reconcile(request: SessionQuarantineRecoveryRequest): SessionQuarantineProof? {
        val verified = recovery.resolve(request.session, request.generation) ?: return null
        val evidence = mutableListOf("Процесс ${request.session.id} сверён с владельцем и остановлен")
        for (quarantine in request.quarantines) {
            val receipt = verified.singleOrNull { quarantine.operationId == "quarantine-${it.operationId.ifBlank { it.id }}" &&
                it.phase in NativeReceiptRecovery.terminal } ?: return null
            evidence += "${receipt.id}: ${receipt.phase} (журнал инструмента)"
        }
        return SessionQuarantineProof(request.quarantines.map { it.operationId }.toSet(), evidence)
    }
}
