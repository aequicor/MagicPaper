package io.aequicor.magicpaper.data.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.test.Test

/**
 * Opt-in benchmark (`-Pmagicpaper.benchmark=true`): what the payload store and the event journal cost a command check,
 * against the user's data on 2026-09-23 — a 3533-key manifest and 2829 journal records. Writes build/storage-cost.txt.
 */
class StorageCostBenchmark {
    @Test fun breakdown() = runBlocking {
        assumeTrue(System.getProperty("magicpaper.benchmark") == "true")
        val root = Files.createTempDirectory("storage-cost-").toFile()
        val report = StringBuilder()
        fun line(name: String, samples: List<Long>) {
            val sorted = samples.sorted()
            fun ms(n: Long) = "%.3f".format(n / 1e6)
            report.appendLine("%-44s n=%4d mean=%8s p50=%8s p90=%8s ms".format(name, samples.size,
                ms(samples.average().toLong()), ms(sorted[sorted.size / 2]), ms(sorted[sorted.size * 9 / 10])))
        }
        try {
            val raw = File(root, "raw").apply { mkdirs() }
            val payload = "x".repeat(900)
            line("create+write 900B, no fsync", measure(300) { i -> FileOutputStream(File(raw, "n$i")).use { it.write(payload.toByteArray()) } })
            line("create+write 900B + fsync", measure(300) { i -> FileOutputStream(File(raw, "s$i")).use { it.write(payload.toByteArray()); it.fd.sync() } })
            line("temp+write+fsync+rename 900B (atomicWrite)", measure(300) { i -> atomic(raw, File(raw, "a$i"), payload.toByteArray()) })
            val manifestBytes = (1..3533).joinToString("\n") { key(it) }.toByteArray()
            report.appendLine("manifest bytes: ${manifestBytes.size}")
            line("atomicWrite of the 3533-key manifest", measure(100) { atomic(raw, File(raw, "manifest.txt"), manifestBytes) })
            line("  of which: join 3533 keys to bytes", measure(100) { (1..3533).joinToString("\n") { key(it) }.toByteArray() })

            val kv = File(root, "kv").apply { mkdirs() }
            val keys = (1..3533).map(::key)
            keys.forEach { File(kv, safe(it) + ".json").writeText(payload) }
            File(kv, "manifest.txt").writeText(keys.joinToString("\n"))
            val store = FileKeyValueStore(kv)
            line("KV write, NEW key (payload + manifest)", measure(100) { store.write("check-input:${"a".repeat(64)}:${UUID.randomUUID()}", payload) })
            line("KV write, existing key, new bytes", measure(100) { i -> store.write(keys[i], payload + i) })
            line("KV write, existing key, same bytes", measure(100) { i -> store.write(keys[i], payload + i) })
            line("KV read", measure(300) { i -> store.read(keys[i]) })

            val backend = FileDurableByteStore(File(root, "persistence"))
            val journal = DurableEventJournal(backend)
            // The user's journal: 2829 records, 1566 of them command-check inputs.
            val streams = (1..300).map { "session-input:$it" }
            val seed = System.nanoTime()
            repeat(1263) { journal.append(streams[it % streams.size], "op", 0, "{\"id\":\"${UUID.randomUUID()}\"}") }
            repeat(1566) { journal.append("command-check:w${it % 8}", "check.input.v1", 0, "{\"id\":\"${UUID.randomUUID()}\"}") }
            report.appendLine("seeded 2829 records in %.1f s".format((System.nanoTime() - seed) / 1e9))
            line("backend.write 300B (chmod + fsync + rename)", measure(100) { i -> backend.write(StorageArea.BLOBS, "bench$i", payload.take(300).toByteArray()) })
            line("backend.read", measure(300) { backend.read(StorageArea.EVENTS, "seq") })
            line("journal.snapshot(check stream), cached", measure(100) { journal.snapshot("command-check:w0") })
            line("journal.append(expected) on a check stream", measure(100) {
                val before = journal.snapshot("command-check:w0")
                checkNotNull(journal.append(before.revision, "check.input.v1", 0, "{}"))
            })
            line("journal.streams() (301+ streams, cached)", measure(30) { journal.streams() })
            line("journal.snapshot after a foreign writer (rescan)", measure(10) {
                DurableEventJournal(backend).append("other", "op", 0, "{}")
                journal.snapshot("command-check:w0")
            })
        } finally { root.deleteRecursively() }
        File("build/storage-cost.txt").writeText(report.toString())
        println(report)
    }

    private inline fun measure(times: Int, block: (Int) -> Unit): List<Long> = (0 until times).map { i ->
        val start = System.nanoTime(); block(i); System.nanoTime() - start
    }
    private fun key(i: Int) = "check-input:${"%064x".format(i)}:${UUID.nameUUIDFromBytes(byteArrayOf(i.toByte(), (i shr 8).toByte()))}"
    private fun safe(key: String) = key.map { c -> if (c.isLetterOrDigit() || c == '-' || c == '.') c else '_' }.joinToString("")
    private fun atomic(dir: File, target: File, bytes: ByteArray) {
        val tmp = File.createTempFile("write-", ".pending", dir)
        try {
            FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { tmp.delete() }
    }
}
