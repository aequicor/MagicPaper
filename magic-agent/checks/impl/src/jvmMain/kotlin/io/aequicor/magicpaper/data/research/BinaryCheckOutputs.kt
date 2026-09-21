package io.aequicor.magicpaper.data.research

import io.aequicor.magicpaper.domain.checks.CheckOutputRef
import io.aequicor.magicpaper.data.checks.CheckOutputLimitExceeded
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Exact stdout is an immutable private artifact. Oversize output fails; it is never truncated. */
internal class BinaryCheckOutputs(private val directory: Path) {
    fun save(id: String, source: Path): CheckOutputRef {
        require(id.isNotBlank())
        val bytes = bytes(source)
        val ref = CheckOutputRef(id, bytes.size.toLong(), digest(bytes))
        Files.createDirectories(directory)
        validateDirectory()
        val path = path(id)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
        }
        check(read(ref).contentEquals(bytes)) { "Binary output write is not confirmed" }
        return ref
    }

    fun read(ref: CheckOutputRef): ByteArray {
        require(ref.id.isNotBlank() && ref.bytes in 0..LIMIT && ref.digest.length == 64) { "Invalid binary output reference" }
        validateDirectory()
        return bytes(path(ref.id)).also {
            check(it.size.toLong() == ref.bytes && digest(it) == ref.digest) { "Binary output identity does not match" }
        }
    }

    private fun validateDirectory() {
        val normalized = directory.toAbsolutePath().normalize()
        check(Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) && !WindowsResearchSandbox.unsafeLink(normalized) &&
            normalized.toRealPath() == normalized) { "Binary output directory is not owned" }
    }

    private fun bytes(path: Path): ByteArray {
        check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !WindowsResearchSandbox.unsafeLink(path)) { "Binary output is unavailable" }
        if (Files.size(path) > LIMIT) throw CheckOutputLimitExceeded()
        // The process group has stopped before this read. Still bound a changed file independently.
        return Files.newInputStream(path).use { input ->
            val bytes = input.readNBytes(LIMIT.toInt() + 1)
            if (bytes.size > LIMIT) throw CheckOutputLimitExceeded()
            bytes
        }
    }

    private fun path(id: String) = directory.resolve("stdout-${digest(id.encodeToByteArray())}.bin")
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    companion object { const val LIMIT = CheckOutputRef.MAX_BYTES }
}
