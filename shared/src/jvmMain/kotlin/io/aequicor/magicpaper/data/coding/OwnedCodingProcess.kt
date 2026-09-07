package io.aequicor.magicpaper.data.coding

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/** PID alone is insufficient: compare start instants, and never kill another live app's process. */
internal class OwnedCodingProcess(private val directory: File) {
    private fun file(id: String) = File(directory, id.replace(Regex("[^a-zA-Z0-9_-]"), "_") + ".process")
    fun record(id: String, process: Process) {
        CodingProcessLifetime.attach(process)
        directory.mkdirs()
        val owner = ProcessHandle.current()
        val value = listOf(process.pid(), process.info().startInstant().orElseThrow().toEpochMilli(), owner.pid(), owner.info().startInstant().orElseThrow().toEpochMilli()).joinToString("\n")
        val tmp = File.createTempFile("owner", ".tmp", directory)
        try {
            FileOutputStream(tmp).use { it.write(value.toByteArray()); it.fd.sync() }
            try { Files.move(tmp.toPath(), file(id).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(tmp.toPath(), file(id).toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { tmp.delete() }
    }
    fun clear(id: String) { file(id).delete() }
    fun belongsTo(id: String, process: Process?): Boolean {
        val lines = file(id).takeIf { it.exists() }?.readLines() ?: return false
        return process != null && lines.firstOrNull()?.toLongOrNull() == process.pid() &&
            lines.getOrNull(1)?.toLongOrNull() == process.info().startInstant().orElse(null)?.toEpochMilli()
    }
    fun reconcile(id: String) {
        val file = file(id)
        if (!file.exists()) return
        val values = file.readLines().map { it.toLongOrNull() }
        require(values.size == 4 && values.all { it != null }) { "Не удалось прочитать владельца процесса $id" }
        fun matching(pid: Long, start: Long): ProcessHandle? = ProcessHandle.of(pid).orElse(null)?.takeIf {
            it.isAlive && it.info().startInstant().orElse(null)?.toEpochMilli() == start
        }
        val process = matching(values[0]!!, values[1]!!)
        if (process != null) {
            val owner = matching(values[2]!!, values[3]!!)
            check(owner == null || owner.pid() == ProcessHandle.current().pid()) { "Процесс выполняется другим экземпляром приложения" }
            val children = process.descendants().use { it.toList() }
            children.asReversed().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.onExit().get(10, TimeUnit.SECONDS)
            children.forEach { if (it.isAlive) it.onExit().get(10, TimeUnit.SECONDS) }
        }
        check(file.delete() || !file.exists()) { "Не удалось очистить запись процесса" }
    }
}
