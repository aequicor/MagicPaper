package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.MediaAsset
import io.aequicor.magicpaper.logging.AppLog
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Content-addressed files outlive drafts and screen visits; no provider URLs or secrets are stored here. */
class FileMediaStore(
    private val root: File = File(System.getProperty("user.home"), ".MagicPaper/media"),
) : MediaStore {
    override val available = true
    private val mutex = locks.computeIfAbsent(root.absoluteFile.normalize().path) { Mutex() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun put(bytes: ByteArray, mimeType: String, width: Int, height: Int, durationSeconds: Double?): MediaAsset =
        access("write media", StorageException.Kind.WRITE) {
            validate(bytes, mimeType)
            require(width in 0..32768 && height in 0..32768)
            require(durationSeconds == null || durationSeconds.isFinite() && durationSeconds >= 0)
            val asset = MediaAsset(digest(bytes), mimeType, bytes.size.toLong(), width, height, durationSeconds)
            val file = assetFile(asset)
            if (file.exists()) verify(asset, file.readBytes()) else atomicWrite(file, bytes)
            asset
        }

    override suspend fun read(asset: MediaAsset): ByteArray = access("read media", StorageException.Kind.READ) {
        val file = assetFile(asset)
        if (!file.isFile || file.length() != asset.byteSize || file.length() > MAX_BYTES)
            throw StorageException("read media", StorageException.Kind.CORRUPT)
        file.readBytes().also { verify(asset, it) }
    }

    override suspend fun localPath(asset: MediaAsset): String = access("open media", StorageException.Kind.READ) {
        val file = assetFile(asset)
        if (!file.isFile || file.length() != asset.byteSize || file.length() > MAX_BYTES)
            throw StorageException("open media", StorageException.Kind.CORRUPT)
        // A restored reference must not lend the native decoder a replaced or partially written file.
        file.inputStream().use { input ->
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            if (hex(hash.digest()) != asset.id) throw StorageException("open media", StorageException.Kind.CORRUPT)
        }
        file.absolutePath
    }

    override suspend fun fingerprint(value: String): String = withContext(Dispatchers.Default) { digest(value.encodeToByteArray()) }

    override suspend fun readRecord(key: String): String? = access("read media operation", StorageException.Kind.READ) {
        val file = recordFile(key)
        if (!file.exists()) null else decodeRecord(file).also {
            if (it.key != key) throw StorageException("read media identity", StorageException.Kind.CORRUPT)
        }.value
    }

    override suspend fun writeRecord(key: String, value: String) = access("write media operation", StorageException.Kind.WRITE) {
        require(key.isNotBlank())
        atomicWrite(recordFile(key), json.encodeToString(Record.serializer(), Record(key, value)).encodeToByteArray())
    }

    override suspend fun records(prefix: String): Map<String, String> = access("list media operations", StorageException.Kind.READ) {
        val directory = File(root, "records")
        if (!directory.exists()) emptyMap() else {
            val files = directory.listFiles() ?: throw StorageException("list media operations", StorageException.Kind.READ)
            files.filter { it.isFile && it.extension == "json" }.map(::decodeRecord)
                .filter { it.key.startsWith(prefix) }.associate { it.key to it.value }
        }
    }

    override suspend fun deleteRecord(key: String) = access("delete media operation", StorageException.Kind.CLEANUP) {
        Files.deleteIfExists(recordFile(key).toPath()); Unit
    }

    override suspend fun deleteAsset(asset: MediaAsset) = access("delete media", StorageException.Kind.CLEANUP) {
        Files.deleteIfExists(assetFile(asset).toPath()); Unit
    }

    override suspend fun clear() = access("clear media", StorageException.Kind.CLEANUP) {
        if (root.exists() && !root.deleteRecursively()) throw StorageException("clear media", StorageException.Kind.CLEANUP)
    }

    private fun decodeRecord(file: File): Record = try {
        json.decodeFromString(Record.serializer(), file.readText())
    } catch (failure: Exception) { throw StorageException("decode media operation", StorageException.Kind.CORRUPT, failure) }

    private fun assetFile(asset: MediaAsset): File {
        require(asset.id.matches(Regex("[a-f0-9]{64}")))
        return File(File(root, "assets"), "${asset.id}.${extension(asset.mimeType)}")
    }
    private fun recordFile(key: String) = File(File(root, "records"), "${digest(key.encodeToByteArray())}.json")
    private fun verify(asset: MediaAsset, bytes: ByteArray) {
        if (bytes.size.toLong() != asset.byteSize || digest(bytes) != asset.id)
            throw StorageException("verify media", StorageException.Kind.CORRUPT)
        validate(bytes, asset.mimeType)
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        Files.createDirectories(target.parentFile.toPath())
        privatePermissions(root, directory = true)
        privatePermissions(target.parentFile, directory = true)
        val temporary = File.createTempFile("media-", ".pending", target.parentFile)
        var primaryFailure: Throwable? = null
        try {
            privatePermissions(temporary, directory = false)
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try { Files.deleteIfExists(temporary.toPath()) }
            catch (cleanup: Exception) {
                if (primaryFailure == null) throw cleanup
                primaryFailure.addSuppressed(cleanup)
                AppLog.error("MediaStore", "temporary_cleanup_failed", cleanup)
            }
        }
    }

    private suspend fun <T> access(operation: String, kind: StorageException.Kind, action: () -> T): T =
        withContext(Dispatchers.IO) { mutex.withLock {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: StorageException) { throw failure }
            catch (failure: Exception) { throw StorageException(operation, kind, failure) }
        } }

    @Serializable private data class Record(val key: String, val value: String)
    companion object {
        const val MAX_BYTES: Long = 256L * 1024 * 1024
        private val locks = ConcurrentHashMap<String, Mutex>()
        private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private fun extension(mime: String) = when (mime) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "video/mp4" -> "mp4"
            else -> throw StorageException("media type", StorageException.Kind.CORRUPT)
        }
        private fun validate(bytes: ByteArray, mime: String) {
            val valid = bytes.isNotEmpty() && bytes.size <= MAX_BYTES && when (mime) {
                "image/png" -> bytes.size >= 24 && bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
                "image/jpeg" -> bytes.size >= 4 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() && bytes[2] == (-1).toByte()
                "image/webp" -> bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" && bytes.copyOfRange(8, 12).decodeToString() == "WEBP"
                "video/mp4" -> bytes.size >= 12 && bytes.copyOfRange(4, 8).decodeToString() == "ftyp"
                else -> false
            }
            if (!valid) throw StorageException("validate media", StorageException.Kind.CORRUPT)
        }
    }
}
