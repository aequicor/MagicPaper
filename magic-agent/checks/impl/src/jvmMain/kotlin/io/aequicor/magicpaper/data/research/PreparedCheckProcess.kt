package io.aequicor.magicpaper.data.research

import io.aequicor.magicpaper.domain.checks.CheckProcessReceipt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** A live resource capability. The command cannot execute until its owner journals the receipt and calls release. */
internal abstract class PreparedCheckProcess : Process() {
    abstract val receipt: CheckProcessReceipt
    abstract fun release()
    abstract suspend fun stopAndConfirm(): NativeCheckCleanup
}

@Serializable
internal data class NativeCheckCleanup(val groupProof: String, val authorityProof: String)

/** Only failures before any process or permission change may use this known, retryable outcome. */
internal class NativeCheckUnavailable(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** The callback commits the complete private ACL bundle before the first grant, returning its durable identity. */
internal typealias CheckAuthorityRecorder = (receiptId: String, payload: ByteArray) -> String

@Serializable
internal data class NativeCheckIdentity(val receipt: CheckProcessReceipt, val startedAt: Long,
    val group: String, val guardianPid: Long? = null, val guardianStartedAt: Long? = null)

@Serializable
private data class NativeCheckCleanupRecord(val receipt: CheckProcessReceipt, val identityDigest: String,
    val cleanup: NativeCheckCleanup)

/** Private immutable files contain identities and proof only, never argv, environment or command output. */
internal class NativeCheckReceiptFiles(private val directory: Path, private val id: String) {
    init { require(id.isNotBlank()) }
    private val json = Json { encodeDefaults = true }
    private val stem = digest(id.encodeToByteArray())
    private val identityPath get() = directory.resolve("native-$stem.json")
    private val cleanupPath get() = directory.resolve("native-$stem.cleanup.json")

    fun record(identity: NativeCheckIdentity) {
        validate(identity)
        write(identityPath, json.encodeToString(NativeCheckIdentity.serializer(), identity).encodeToByteArray())
    }

    fun confirm(receipt: CheckProcessReceipt, cleanup: NativeCheckCleanup): NativeCheckCleanup {
        require(cleanup.groupProof.isNotBlank() && cleanup.authorityProof.isNotBlank())
        val identity = requireNotNull(read(identityPath)) { "Native process identity is missing" }
        check(json.decodeFromString(NativeCheckIdentity.serializer(), identity.decodeToString()).receipt == receipt)
        val record = NativeCheckCleanupRecord(receipt, digest(identity), cleanup)
        write(cleanupPath, json.encodeToString(NativeCheckCleanupRecord.serializer(), record).encodeToByteArray())
        return requireNotNull(cleanup(receipt))
    }

    fun cleanup(receipt: CheckProcessReceipt): NativeCheckCleanup? {
        require(receipt.id == id)
        val identity = read(identityPath)
        val native = identity?.let { json.decodeFromString(NativeCheckIdentity.serializer(), it.decodeToString()).also(::validate) }
        if (native != null) check(native.receipt == receipt) { "Native process identity does not match" }
        val completed = read(cleanupPath) ?: return null
        checkNotNull(identity) { "Native cleanup lost its process identity" }
        checkNotNull(native)
        val saved = json.decodeFromString(NativeCheckCleanupRecord.serializer(), completed.decodeToString())
        check(native.receipt == receipt && saved.receipt == receipt && saved.identityDigest == digest(identity) &&
            native.startedAt > 0 && native.group.isNotBlank() && saved.cleanup.groupProof.isNotBlank() && saved.cleanup.authorityProof.isNotBlank()) {
            "Native cleanup does not match the recorded process"
        }
        return saved.cleanup
    }

    private fun validate(identity: NativeCheckIdentity) {
        require(identity.receipt.id == id && identity.receipt.pid > 0 && identity.receipt.kind.isNotBlank() &&
            identity.startedAt > 0 && identity.group.isNotBlank() &&
            (identity.receipt.authorityReceipt?.isNotBlank() != false) &&
            ((identity.guardianPid == null && identity.guardianStartedAt == null) ||
                (identity.guardianPid != null && identity.guardianPid > 0 && identity.guardianStartedAt != null && identity.guardianStartedAt > 0))) {
            "Invalid native process identity"
        }
    }

    private fun read(path: Path): ByteArray? {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= 1_048_576) { "Invalid native receipt file" }
        return Files.readAllBytes(path)
    }

    private fun write(path: Path, bytes: ByteArray) {
        Files.createDirectories(directory)
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "Native receipt directory is unavailable" }
        read(path)?.let { check(it.contentEquals(bytes)) { "Native receipt is immutable" }; return }
        // CREATE_NEW forbids replacing a prior receipt. A torn write is retained and fails closed on read.
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        check(read(path)?.contentEquals(bytes) == true) { "Native receipt write is not confirmed" }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

/** Explicit inspection reads prior native evidence; it never signals a process or infers cleanup from PID absence. */
internal fun readNativeCheckCleanup(receipt: CheckProcessReceipt, receiptDirectory: Path): NativeCheckCleanup? =
    NativeCheckReceiptFiles(receiptDirectory, receipt.id).cleanup(receipt)
