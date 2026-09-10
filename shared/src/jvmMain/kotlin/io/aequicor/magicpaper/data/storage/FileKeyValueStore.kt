package io.aequicor.magicpaper.data.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Десктопное хранилище: папка ~/.MagicPaper в домашнем каталоге пользователя.
 * Не требует прав администратора (изолированная среда пользователя);
 * удаляется вместе с папкой при деинсталляции.
 */
class FileKeyValueStore internal constructor(private val root: File) : KeyValueStore {

    constructor(rootName: String = ".MagicPaper") : this(File(System.getProperty("user.home"), rootName))

    init { root.mkdirs() }
    private val manifest: File = File(root, "manifest.txt")
    private val keySet: MutableSet<String> = LinkedHashSet(loadManifest())

    override fun read(key: String): String? = synchronized(this) {
        // A crash between the data rename and manifest update must not hide a committed key.
        file(key).takeIf { it.isFile }?.readText()
    }

    override fun write(key: String, value: String) = synchronized(this) {
        atomicWrite(file(key), value)
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

    private fun loadManifest(): List<String> =
        runCatching { manifest.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    private fun saveManifest() {
        atomicWrite(manifest, keySet.joinToString("\n"))
    }

    private fun atomicWrite(target: File, value: String) {
        val tmp = File.createTempFile("write-", ".pending", root)
        try {
            FileOutputStream(tmp).use { output -> output.write(value.toByteArray(Charsets.UTF_8)); output.fd.sync() }
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { tmp.delete() }
    }
}
