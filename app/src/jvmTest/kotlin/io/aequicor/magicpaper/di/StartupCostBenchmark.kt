package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.data.storage.FileKeyValueStore
import io.aequicor.magicpaper.data.storage.desktopPersistenceStores
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test

/**
 * Opt-in benchmark (`-Pmagicpaper.benchmark=true`): how long the application takes to become ready, and so to paint the
 * session list, on a copy of real data. `-Pmagicpaper.benchmark.data=<dir>` names the source (`~/.MagicPaper` by default);
 * it is only read. Each round starts the whole runtime with the native coding feature over its own fresh copy of the
 * key-value files and the persistence directory without its secrets, on one main thread like the desktop's, with an agent runtime that
 * refuses to run anything, and reports the `phase.finished`, `coding.journal restored` and `coding sessions.listed`
 * lines the application itself writes. Planning workspaces and task worktrees are not copied: the fixture has none.
 * Writes build/startup-cost.txt.
 */
class StartupCostBenchmark {
    private val bridge = object : ProfileBridge {
        override val supportsFilePicker = false
        override suspend fun export(json: String) = false
        override suspend fun import(): String? = null
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    @Test fun start() {
        assumeTrue(System.getProperty("magicpaper.benchmark") == "true")
        val source = File(System.getProperty("magicpaper.benchmark.data")?.takeIf { it.isNotBlank() }
            ?: File(System.getProperty("user.home"), ".MagicPaper").path)
        check(File(source, "persistence/events").isDirectory) { "No journal under $source" }
        val report = StringBuilder()
        repeat(ROUNDS) { round ->
            val root = copy(source)
            val main = newSingleThreadContext("benchmark-main")
            val output = System.out
            val captured = ByteArrayOutputStream()
            try {
                Dispatchers.setMain(main)
                val home = File(System.getProperty("user.home")).toPath()
                val store = FileKeyValueStore(home.relativize(root.toPath()).toString())
                val persistence = desktopPersistenceStores(root)
                val computer = io.aequicor.magicpaper.data.computer.DesktopComputerUse(persistence.events)
                val runtime = buildRuntime(store, persistence, bridge, NavigationSessionConfig(),
                    platformDefinitions = { scope -> nativeRuntimeBindings(scope, RefusingRuntime, null,
                        LocalPlanningWorkspace(), UnavailableTaskWorkspace, null) },
                    runtimeExtensions = { listOf(NativeRuntimeExtension(get(), computer, {}, {})) },
                    featurePlugins = { get<CodingFeature>().plugins },
                    onPlatformClosed = { computer.close() })
                System.setOut(PrintStream(TeeStream(output, captured), true))
                val started = System.nanoTime()
                val state = runBlocking {
                    runtime.start()
                    withTimeout(120_000) { runtime.ready.first { it != RuntimeState.Loading } }
                }
                val elapsed = (System.nanoTime() - started) / 1_000_000
                System.setOut(output)
                runBlocking { runtime.close(); runtime.awaitClosed() }
                report.appendLine("round ${round + 1}: $state after $elapsed ms")
                captured.toString(Charsets.UTF_8).lineSequence().filter { it.startsWith("{") }.forEach { line ->
                    val entry = Json.parseToJsonElement(line).jsonObject
                    val component = entry.getValue("component").jsonPrimitive.content
                    val event = entry.getValue("event").jsonPrimitive.content
                    val fields = entry.getValue("fields").jsonObject.mapValues { it.value.jsonPrimitive.content }
                    val shown = event == "phase.finished" || event == "phase.failed" ||
                        component == "coding.journal" && event == "restored" || component == "coding" && event == "sessions.listed" ||
                        component == "runtime" && event in setOf("ready", "restore.failed")
                    if (shown) report.appendLine("  %-8s %-18s %s".format(component, event,
                        fields.entries.joinToString(" ") { "${it.key}=${it.value}" }))
                }
            } finally {
                System.setOut(output)
                Dispatchers.resetMain()
                main.close()
                root.deleteRecursively()
            }
        }
        File("build/startup-cost.txt").writeText(report.toString())
        println(report)
    }

    private class TeeStream(private val first: java.io.OutputStream, private val second: java.io.OutputStream) : java.io.OutputStream() {
        override fun write(b: Int) { first.write(b); synchronized(second) { second.write(b) } }
        override fun write(b: ByteArray, off: Int, len: Int) { first.write(b, off, len); synchronized(second) { second.write(b, off, len) } }
        override fun flush() = first.flush()
    }

    /** Every top-level key and the durable persistence directory, but not its secrets: providers restore without keys. */
    private fun copy(source: File): File {
        val root = Files.createTempDirectory("startup-cost-").toFile()
        File(source, "persistence").listFiles().orEmpty().filter { it.name != "secrets" }
            .forEach { it.copyRecursively(File(root, "persistence/${it.name}")) }
        source.listFiles { file -> file.isFile }.orEmpty().forEach { Files.copy(it.toPath(), File(root, it.name).toPath()) }
        return root
    }

    /** Starting the application restores; it never runs an agent. */
    private object RefusingRuntime : CodingRuntime {
        override val supported = true
        override val rootPath = "/benchmark/runtime"
        override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
        override fun ensureReady() = kotlinx.coroutines.flow.flowOf(RuntimeStatus(RuntimePhase.READY))
        override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?,
            attachments: List<Attachment>) = kotlinx.coroutines.flow.flow<CodingEvent> { error("Unexpected native run") }
        override fun abort(sessionId: String) = Unit
        override fun abortAll() = Unit
        override suspend fun uninstall() = Unit
    }

    private companion object { const val ROUNDS = 3 }
}
