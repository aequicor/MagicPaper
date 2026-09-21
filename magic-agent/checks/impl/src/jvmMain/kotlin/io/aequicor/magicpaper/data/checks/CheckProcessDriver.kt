package io.aequicor.magicpaper.data.checks

import io.aequicor.magicpaper.domain.checks.*

/** External-process boundary, injected in tests. It never chooses admission or retries. */
internal interface CheckProcessDriver {
    suspend fun prepare(command: CheckCommand, receiptId: String, authority: CheckAuthorityRecorder): PreparedCommandCheck
    /** Filesystem query only. The interpreter owns every metadata subprocess before preparation. */
    suspend fun needsGitMetadata(command: CheckCommand): Boolean = false
    suspend fun prepareWithMetadata(command: CheckCommand, receiptId: String, authority: CheckAuthorityRecorder,
        metadata: CheckGitMetadata?): PreparedCommandCheck {
        require(metadata == null) { "This process driver does not accept Git metadata" }
        return prepare(command, receiptId, authority)
    }
    suspend fun probeWorkspace(): String? = null
    suspend fun createProbe(ref: CheckRef): CheckProbe = error("This driver does not require a sandbox probe")
    suspend fun readOutput(output: CheckOutputRef): ByteArray = error("Binary command output is unavailable")
    suspend fun cleanup() = Unit
}
internal data class CheckGitMetadata(val parent: CheckRef, val protectedResource: String,
    val outputs: Map<CheckGitMetadataQuery, CheckOutputRef>)
internal interface CheckProbe {
    val command: CheckCommand
    suspend fun verify(result: CheckResult)
}
internal fun interface CheckAuthorityRecorder {
    /** Payload is private native rollback evidence, durably saved before the first privilege change. */
    suspend fun record(id: String, payload: ByteArray): String
}
internal data class CheckCleanup(val groupStopped: String, val authorityRestored: String)
internal interface PreparedCommandCheck {
    val receipt: CheckProcessReceipt
    suspend fun release()
    suspend fun awaitResult(progress: (String) -> Unit): CheckResult
    suspend fun stopAndConfirm(): CheckCleanup
    /** Called only after both cleanup proofs are journaled. */
    suspend fun attest(): String
    suspend fun discard()
}
/** The adapter has verified that no command escaped and every acquired permission was restored. */
internal class CheckNotDispatched(val safeReason: String, val restoredAuthority: String? = null, cause: Throwable? = null) :
    IllegalStateException(safeReason, cause)
internal class CheckTimedOut : IllegalStateException("Проверка остановлена по таймауту")
internal class CheckOutputLimitExceeded : IllegalStateException("Вывод команды превышает допустимый размер")
internal class CheckPreparationCancelled(val original: kotlinx.coroutines.CancellationException) :
    kotlinx.coroutines.CancellationException("Check cancelled before native preparation") {
    init { initCause(original) }
}
