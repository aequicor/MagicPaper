package io.aequicor.magicpaper.data.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Десктопное хранилище: папка ~/.MagicPaper в домашнем каталоге пользователя.
 * Не требует прав администратора (изолированная среда пользователя);
 * удаляется вместе с папкой при деинсталляции.
 */
class FileKeyValueStore internal constructor(private val root: File) : BatchReadKeyValueStore {

    constructor(rootName: String = ".MagicPaper") : this(File(System.getProperty("user.home"), rootName))

    init { root.mkdirs() }
    private val manifest: File = File(root, "manifest.txt")
    private val keySet: MutableSet<String> = LinkedHashSet(loadManifest())

    override fun read(key: String): String? = synchronized(this) {
        // A crash between the data rename and manifest update must not hide a committed key.
        file(key).takeIf { it.isFile }?.readText()
    }

    /**
     * Journal replays read every payload they ever stored, one file each. Where an open is slow
     * (on-access antivirus scanning on Windows, a spinning disk) reading them one after another
     * held application start for tens of seconds, so the opens overlap. Holding the monitor keeps
     * every writer out until the whole batch is read.
     */
    override fun readBatch(keys: Collection<String>): Map<String, String?> = synchronized(this) {
        val distinct = keys.distinct()
        if (distinct.size < 2) return distinct.associateWith(::read)
        val readers = Executors.newFixedThreadPool(minOf(distinct.size, READ_PARALLELISM)) { task ->
            Thread(task, "key-value-read").apply { isDaemon = true }
        }
        try {
            val reads = distinct.map { key -> readers.submit(Callable { file(key).takeIf { it.isFile }?.readText() }) }
            distinct.zip(reads).associate { (key, read) ->
                key to try { read.get() } catch (failure: ExecutionException) { throw failure.cause ?: failure }
            }
        } finally { readers.shutdownNow() }
    }

    override fun write(key: String, value: String) = synchronized(this) {
        val target = file(key)
        val bytes = value.toByteArray(Charsets.UTF_8)
        // Checkpoints re-project state that is usually unchanged. Rewriting identical bytes costs a
        // flush, a rename and, on Windows, a fresh antivirus scan of the new file, for nothing.
        if (!holds(target, bytes)) atomicWrite(target, bytes)
        if (keySet.add(key)) saveManifest()
    }

    override fun delete(key: String) = synchronized(this) {
        file(key).delete()
        if (keySet.remove(key)) saveManifest()
    }

    override fun keys(prefix: String): List<String> = synchronized(this) {
        (keySet + root.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }.map { it.nameWithoutExtension })
            .filter { it.startsWith(prefix) }.distinct()
    }

    override fun clear() = synchronized(this) {
        keySet.forEach { file(it).delete() }
        keySet.clear()
        saveManifest()
    }

    override val description: String get() = root.absolutePath

    private fun file(key: String): File {
        val safe = key.map { c -> if (c.isLetterOrDigit() || c == '-' || c == '.') c else '_' }
            .joinToString("")
        val legacyName = "$safe.json"
        // Preserve existing files; filesystem component limits apply to UTF-8 bytes.
        if (legacyName.toByteArray(Charsets.UTF_8).size <= 255) return File(root, legacyName)
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        // The manifest retains the original key. Do not expose hashes as legacy JSON keys.
        return File(root, "$digest.data")
    }

    private companion object { const val READ_PARALLELISM = 16 }

    private fun loadManifest(): List<String> =
        runCatching { manifest.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    private fun saveManifest() {
        atomicWrite(manifest, keySet.joinToString("\n").toByteArray(Charsets.UTF_8))
    }

    private fun holds(target: File, bytes: ByteArray): Boolean =
        target.length() == bytes.size.toLong() && target.isFile && target.readBytes().contentEquals(bytes)

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File.createTempFile("write-", ".pending", root)
        try {
            FileOutputStream(tmp).use { output -> output.write(bytes); output.fd.sync() }
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { tmp.delete() }
    }
}
