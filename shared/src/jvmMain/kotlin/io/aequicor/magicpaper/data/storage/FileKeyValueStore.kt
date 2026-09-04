package io.aequicor.magicpaper.data.storage

import java.io.File

/**
 * Десктопное хранилище: папка ~/.MagicPaper в домашнем каталоге пользователя.
 * Не требует прав администратора (изолированная среда пользователя);
 * удаляется вместе с папкой при деинсталляции.
 */
class FileKeyValueStore(rootName: String = ".MagicPaper") : KeyValueStore {

    private val root: File = File(System.getProperty("user.home"), rootName).apply { mkdirs() }
    private val manifest: File = File(root, "manifest.txt")
    private val keySet: MutableSet<String> = LinkedHashSet(loadManifest())

    override fun read(key: String): String? = synchronized(this) {
        if (key !in keySet) return null
        file(key).takeIf { it.isFile }?.readText()
    }

    override fun write(key: String, value: String) = synchronized(this) {
        file(key).writeText(value)
        if (keySet.add(key)) saveManifest()
    }

    override fun delete(key: String) = synchronized(this) {
        file(key).delete()
        if (keySet.remove(key)) saveManifest()
    }

    override fun keys(prefix: String): List<String> = synchronized(this) {
        keySet.filter { it.startsWith(prefix) }.toList()
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
        return File(root, "$safe.json")
    }

    private fun loadManifest(): List<String> =
        runCatching { manifest.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    private fun saveManifest() {
        manifest.writeText(keySet.joinToString("\n"))
    }
}
