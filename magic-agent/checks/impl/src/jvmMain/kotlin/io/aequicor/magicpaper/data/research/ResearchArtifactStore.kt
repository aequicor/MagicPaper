package io.aequicor.magicpaper.data.research

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.security.MessageDigest

/** Existing manifest wire/path is retained. Corruption never becomes permission to overwrite an empty project. */
internal class ResearchArtifactStore(private val root: Path) {
    data class Snapshot(val project: Path, val bytes: String?, val files: Map<Path, String>)
    fun read(project: Path): Snapshot {
        val path = manifest(project)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Snapshot(project, null, emptyMap())
        check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= 32_000_000) { "Invalid research artifact manifest" }
        val bytes = Files.readString(path)
        val files = Json.parseToJsonElement(bytes).jsonObject.map { (name, value) ->
            val file = Paths.get(name)
            val digest = value.jsonPrimitive.also { check(it.isString) }.content
            check(file.isAbsolute && file.normalize() == file && file.startsWith(project) && digest.matches(Regex("[0-9a-f]{64}")))
            file to digest
        }.toMap()
        return Snapshot(project, bytes, files)
    }
    fun commit(before: Snapshot, directories: List<Path>, receiptId: String): String {
        check(read(before.project).bytes == before.bytes) { "Research artifact revision changed" }
        val files = before.files.filterKeys { path -> directories.none { path.startsWith(it) } } + ResearchWorkspacePolicy.snapshot(directories)
        val encoded = JsonObject(files.toSortedMap(compareBy(Path::toString)).mapKeys { it.key.toString() }.mapValues { JsonPrimitive(it.value) }).toString()
        val proof = digest("$receiptId\u0000${before.project}\u0000${before.bytes}\u0000$encoded")
        val receipt = root.resolve("owned").resolve("artifacts-${digest(receiptId)}.json")
        Files.createDirectories(receipt.parent)
        val evidence = buildJsonObject { put("receipt", receiptId); put("proof", proof); put("manifest", encoded) }.toString()
        if (Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)) {
            check(Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS) && Files.readString(receipt) == evidence)
        } else forceWrite(receipt, evidence, new = true)
        val temp = Files.createTempFile(root, "artifacts-", ".tmp")
        var failure: Throwable? = null
        try {
            forceWrite(temp, encoded, new = false)
            Files.move(temp, manifest(before.project), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            check(read(before.project).bytes == encoded)
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            try { Files.deleteIfExists(temp) } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup
                if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
        }
        return proof
    }
    private fun manifest(project: Path) = root.resolve("artifacts-${key(project.toString())}.json")
    private fun forceWrite(path: Path, value: String, new: Boolean) {
        val options = if (new) arrayOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            else arrayOf(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        FileChannel.open(path, *options).use { channel ->
            val buffer = ByteBuffer.wrap(value.toByteArray(Charsets.UTF_8))
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }
    companion object {
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        fun key(value: String) = digest(value).take(32)
    }
}
