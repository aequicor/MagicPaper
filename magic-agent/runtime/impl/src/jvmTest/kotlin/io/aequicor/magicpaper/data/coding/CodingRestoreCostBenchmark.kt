package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.FileKeyValueStore
import io.aequicor.magicpaper.data.storage.desktopPersistenceStores
import io.aequicor.magicpaper.domain.CodingMachine
import io.aequicor.magicpaper.domain.tools.toolArgumentsFingerprint
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/**
 * Opt-in benchmark (`-Pmagicpaper.benchmark=true`): what restoring the coding journal costs the application's
 * startup, on a copy of real data. `-Pmagicpaper.benchmark.data=<dir>` names the source (`~/.MagicPaper` by default);
 * it is only read — each round restores its own fresh copy of the journal and the coding keys, through the production
 * file stores, and reports the store's own `coding.journal restored` line. The first round is the one startup pays:
 * a fresh JVM, nothing compiled yet. The largest payload is then taken apart into the passes a replay makes over it.
 * Writes build/coding-restore-cost.txt.
 */
class CodingRestoreCostBenchmark {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun restore() = runBlocking {
        assumeTrue(System.getProperty("magicpaper.benchmark") == "true")
        val source = File(System.getProperty("magicpaper.benchmark.data")?.takeIf { it.isNotBlank() }
            ?: File(System.getProperty("user.home"), ".MagicPaper").path)
        check(File(source, "persistence/events").isDirectory) { "No journal under $source" }
        val report = StringBuilder()
        repeat(ROUNDS) { round ->
            val root = copy(source)
            try {
                val home = File(System.getProperty("user.home")).toPath()
                val store = FileKeyValueStore(home.relativize(root.toPath()).toString())
                val events = desktopPersistenceStores(root).events
                val owner = CodingJournalStore(BackgroundCodingProjectRepository(JsonCodingProjectRepository(store, json)),
                    events, StoredCodingPayloads(store, json), json)
                val started = System.nanoTime()
                owner.start()
                val elapsed = (System.nanoTime() - started) / 1_000_000
                val restored = AppLog.history().last { it.component == "coding.journal" && it.event == "restored" }
                report.appendLine("round ${round + 1}: start() ${elapsed} ms; " +
                    restored.fields.entries.joinToString(" ") { "${it.key}=${it.value}" })
            } finally { root.deleteRecursively() }
        }
        val largest = source.listFiles { file -> file.name.startsWith("coding-input_") }.orEmpty().maxBy { it.length() }
        report.appendLine("largest payload: ${largest.length() / 1_000_000} MB")
        repeat(PASS_ROUNDS) { round ->
            val raw = largest.readText()
            val element = pass(report, round, "parseToJsonElement") { json.parseToJsonElement(raw).jsonObject }
            pass(report, round, "fingerprint (canonical text + SHA-256)") { toolArgumentsFingerprint(element) }
            val input = pass(report, round, "decodeFromJsonElement") {
                json.decodeFromJsonElement(CodingMachine.Input.serializer(), element.getValue("input"))
            }
            pass(report, round, "reduce") { CodingMachine.reduce(CodingMachine.initial(), input) }
        }
        File("build/coding-restore-cost.txt").writeText(report.toString())
        println(report)
    }

    private inline fun <T> pass(report: StringBuilder, round: Int, name: String, block: () -> T): T {
        val started = System.nanoTime()
        return block().also { report.appendLine("  pass ${round + 1} %-40s %6d ms".format(name, (System.nanoTime() - started) / 1_000_000)) }
    }

    /** The journal and every coding key; nothing else the application keeps is read by this owner. */
    private fun copy(source: File): File {
        val root = Files.createTempDirectory("coding-restore-").toFile()
        val events = File(root, "persistence/events").apply { mkdirs() }
        File(source, "persistence/events").listFiles().orEmpty().forEach { Files.copy(it.toPath(), File(events, it.name).toPath()) }
        File(source, "persistence/control").takeIf { it.isDirectory }?.copyRecursively(File(root, "persistence/control"))
        source.listFiles { file -> file.isFile && (file.name.startsWith("coding") || file.name == "manifest.txt") }.orEmpty()
            .forEach { Files.copy(it.toPath(), File(root, it.name).toPath()) }
        return root
    }

    private companion object {
        const val ROUNDS = 3
        const val PASS_ROUNDS = 2
    }
}
