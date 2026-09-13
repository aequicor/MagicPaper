package io.aequicor.magicpaper.data.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Private app-owned plaintext files. Filenames encode identifiers, never stored values. */
class FileDurableByteStore(private val root: File) : DurableByteStore {
    private val mutex = Mutex()
    private val draftMutex by lazy { locks.computeIfAbsent(root.canonicalPath) { Mutex() } }
    override suspend fun <T> withDraftLock(block: suspend () -> T): T = draftMutex.withLock { block() }
    override suspend fun values(area: StorageArea): List<ByteArray> = access("list", StorageException.Kind.READ) {
        val directory = File(root, area.storeName)
        if (!directory.exists()) emptyList() else {
            val files = directory.listFiles() ?: throw java.io.IOException("Cannot list records")
            files.filter { it.isFile && it.extension == "data" }.map { it.readBytes() }
        }
    }
    private companion object { val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>() }


    override suspend fun read(area: StorageArea, key: String): ByteArray? = access("read", StorageException.Kind.READ) {
        val target = file(area, key)
        if (!target.exists()) null else target.readBytes()
    }

    override suspend fun write(area: StorageArea, key: String, bytes: ByteArray): Unit = access("write", StorageException.Kind.WRITE) {
        val target = file(area, key)
        Files.createDirectories(root.toPath())
        privatePermissions(root, directory = true)
        Files.createDirectories(target.parentFile.toPath())
        privatePermissions(target.parentFile, directory = true)
        val temporary = File.createTempFile("commit-", ".pending", target.parentFile)
        try {
            privatePermissions(temporary, directory = false)
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
    }

    override suspend fun delete(area: StorageArea, key: String): Unit = access("delete", StorageException.Kind.WRITE) {
        Files.deleteIfExists(file(area, key).toPath())
        Unit
    }

    override suspend fun clear(area: StorageArea): Unit = access("clear", StorageException.Kind.WRITE) {
        val directory = File(root, area.storeName)
        if (directory.exists() && !directory.deleteRecursively()) throw java.io.IOException("clear failed")
    }

    private fun file(area: StorageArea, key: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(key.encodeToByteArray())
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        return File(File(root, area.storeName), "$name.data")
    }

    private suspend fun <T> access(operation: String, kind: StorageException.Kind, action: () -> T): T =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try { action() }
                catch (error: CancellationException) { throw error }
                catch (failure: Exception) { throw StorageException(operation, kind, failure) }
            }
        }
}

internal fun privatePermissions(file: File, directory: Boolean) {
    if (Files.getFileStore(file.toPath()).supportsFileAttributeView("posix")) {
        val permissions = mutableSetOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        if (directory) permissions += PosixFilePermission.OWNER_EXECUTE
        Files.setPosixFilePermissions(file.toPath(), permissions)
    }
}

fun androidPersistenceStores(context: android.content.Context, journalId: String = "main", fallbackJournalId: String? = null): PersistenceStores =
    persistenceStores(FileDurableByteStore(File(context.filesDir, "persistence")), journalId, fallbackJournalId = fallbackJournalId)
