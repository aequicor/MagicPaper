package io.aequicor.magicpaper.data.research

import io.aequicor.magicpaper.data.checks.*
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.checks.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test

/**
 * Opt-in benchmark (`-Pmagicpaper.benchmark=true`): where the wall clock of one GIT_READ_ONLY check goes, on real file
 * stores shaped like the user's data and the real OS sandbox. Writes build/git-read-cost.txt.
 */
class GitReadCheckCostBenchmark {
    private class Meter {
        val nanos = ConcurrentHashMap<String, AtomicLong>()
        val counts = ConcurrentHashMap<String, AtomicLong>()
        inline fun <T> time(name: String, block: () -> T): T {
            val start = System.nanoTime()
            try { return block() } finally {
                nanos.computeIfAbsent(name) { AtomicLong() }.addAndGet(System.nanoTime() - start)
                counts.computeIfAbsent(name) { AtomicLong() }.incrementAndGet()
            }
        }
        fun reset() { nanos.clear(); counts.clear() }
        fun dump(divisor: Int) = nanos.keys.sorted().joinToString("\n") { name ->
            "  %-34s %7.2f ms  x%5.1f".format(name, nanos.getValue(name).get() / 1e6 / divisor, counts.getValue(name).get().toDouble() / divisor)
        }
    }

    private class TimedStore(private val delegate: KeyValueStore, private val meter: Meter) : KeyValueStore by delegate {
        private val known = HashSet(delegate.keys(""))
        override fun read(key: String) = meter.time("kv.read") { delegate.read(key) }
        override fun write(key: String, value: String) = meter.time(if (known.add(key)) "kv.write.new-key" else "kv.write.existing") { delegate.write(key, value) }
    }

    private class TimedBackend(private val delegate: DurableByteStore, private val meter: Meter) : DurableByteStore by delegate {
        override suspend fun read(area: StorageArea, key: String) = meter.time("backend.read") { delegate.read(area, key) }
        override suspend fun write(area: StorageArea, key: String, bytes: ByteArray) = meter.time("backend.write(fsync)") { delegate.write(area, key, bytes) }
        override suspend fun values(area: StorageArea) = meter.time("backend.values(scan)") { delegate.values(area) }
    }

    private class TimedJournal(private val delegate: EventJournal, private val meter: Meter) : EventJournal by delegate {
        override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String) =
            meter.time("journal.append") { delegate.append(expected, operation, at, detail) }
        override suspend fun snapshot(stream: String) = meter.time("journal.snapshot") { delegate.snapshot(stream) }
        override suspend fun streams() = meter.time("journal.streams") { delegate.streams() }
    }

    private class TimedDriver(private val delegate: CheckProcessDriver, private val meter: Meter) : CheckProcessDriver by delegate {
        override suspend fun prepareWithMetadata(command: CheckCommand, receiptId: String, authority: io.aequicor.magicpaper.data.checks.CheckAuthorityRecorder,
            metadata: CheckGitMetadata?): PreparedCommandCheck {
            val prepared = meter.time("driver.prepare(spawn)") { delegate.prepareWithMetadata(command, receiptId, authority, metadata) }
            return object : PreparedCommandCheck by prepared {
                override suspend fun release() = meter.time("driver.release") { prepared.release() }
                override suspend fun awaitResult(progress: (String) -> Unit) = meter.time("driver.awaitResult(poll)") { prepared.awaitResult(progress) }
                override suspend fun stopAndConfirm() = meter.time("driver.stopAndConfirm") { prepared.stopAndConfirm() }
                override suspend fun attest() = meter.time("driver.attest") { prepared.attest() }
                override suspend fun discard() = meter.time("driver.discard") { prepared.discard() }
            }
        }
        override suspend fun readOutput(output: CheckOutputRef) = meter.time("driver.readOutput") { delegate.readOutput(output) }
    }

    @Test fun breakdown() = runBlocking {
        assumeTrue(System.getProperty("magicpaper.benchmark") == "true")
        val root = Files.createTempDirectory("git-read-cost-")
        val report = StringBuilder()
        try {
            // The user's data on 2026-09-23: 3533 payload keys, 2829 records in 125 streams, 1565 of them
            // command-check inputs in 3 check workspaces (about 200 finished checks).
            val projects = (1..3).map { i -> Files.createDirectories(root.resolve("project$i")).also {
                git(it, "init", "-q", "-b", "main"); git(it, "commit", "--allow-empty", "-q", "-m", "base") } }
            val kv = Files.createDirectories(root.resolve("kv")).toFile()
            val keys = (1..3533 - 1565 - 400).map { "seed:${"%064x".format(it)}:${UUID.randomUUID()}" }
            keys.forEach { File(kv, it.map { c -> if (c.isLetterOrDigit() || c == '-' || c == '.') c else '_' }.joinToString("") + ".json").writeText("{}") }
            File(kv, "manifest.txt").writeText(keys.joinToString("\n"))
            val rawStore = FileKeyValueStore::class.java.getDeclaredConstructor(File::class.java).newInstance(kv)
            val rawBackend = FileDurableByteStore(root.resolve("persistence").toFile())
            DurableEventJournal(rawBackend).let { seed -> repeat(1264) { seed.append("session-input:${it % 122}", "op", 0, "{}") } }

            val meter = Meter()
            fun owner() = DefaultCommandChecks(TimedJournal(DurableEventJournal(TimedBackend(rawBackend, meter)), meter),
                TimedStore(rawStore, meter), TimedDriver(SandboxCheckDriver(root.resolve("checks"), 120_000), meter))
            suspend fun check(owner: DefaultCommandChecks, project: Path, n: Int): Pair<Long, Long> {
                val resource = project.toRealPath().toString()
                val gitDir = project.resolve(".git").toRealPath().toString()
                val scope = CheckScope("bench", "git-read-$n", UUID.randomUUID().toString(), 0)
                val command = CheckCommand(CheckRef(scope, "read:1"), resource, CheckGitReadQuery.IS_WORKTREE.arguments(),
                    policy = CheckPolicy.GIT_READ_ONLY, outputMode = CheckOutputMode.BINARY_STDOUT,
                    protectedResource = resource, affectedResources = setOf(resource, gitDir))
                val start = System.nanoTime()
                val result = meter.time("TOTAL run()") { owner.run(command) }
                val ran = System.nanoTime()
                check(result.exitCode == 0) { result.toString() }
                val output = meter.time("TOTAL readOutput()") { owner.readOutput(command.ref) }
                check(output.decodeToString().trim() == "true")
                return (ran - start) to (System.nanoTime() - ran)
            }

            // Baselines: what the process itself costs without any owner around it.
            val project = projects.first()
            val plain = (1..10).map { timed { git(project, "rev-parse", "--is-inside-work-tree") } }
            report.appendLine("plain git rev-parse:        mean %.1f ms".format(plain.average() / 1e6))
            if (System.getProperty("os.name").startsWith("Mac")) {
                val sandboxed = (1..10).map { timed { run(project, listOf("/usr/bin/sandbox-exec", "-p",
                    MacResearchSandbox.profile(ResearchWorkspacePolicy.metadataOnly(project, Files.createTempDirectory("s")), SeatbeltMembership()), "--",
                    "git", "rev-parse", "--is-inside-work-tree")) } }
                report.appendLine("sandbox-exec git rev-parse: mean %.1f ms".format(sandboxed.average() / 1e6))
            }

            val history = (System.getProperty("magicpaper.benchmark.history") ?: "200").toInt()
            val warm = owner()
            repeat(history) { check(warm, projects[if (it % 8 == 0) 1 + it / 8 % 2 else 0], it) }
            warm.close()
            report.appendLine("history: $history checks journaled in 3 workspaces")

            // A fresh owner over the same stores is the first check after launch.
            meter.reset()
            val fresh = owner()
            val first = check(fresh, project, 100_000)
            report.appendLine("FIRST check after restart: run %.1f ms, readOutput %.1f ms".format(first.first / 1e6, first.second / 1e6))
            report.appendLine(meter.dump(1))
            meter.reset()
            val steady = 20
            repeat(steady) { check(fresh, project, 200_000 + it) }
            report.appendLine("STEADY check (mean of $steady):")
            report.appendLine(meter.dump(steady))
            fresh.close()
        } finally { root.toFile().deleteRecursively() }
        File("build/git-read-cost.txt").writeText(report.toString())
        println(report)
    }

    private inline fun timed(block: () -> Unit): Long { val start = System.nanoTime(); block(); return System.nanoTime() - start }
    private fun git(dir: Path, vararg arguments: String) = run(dir, listOf("git", "-c", "user.name=b", "-c", "user.email=b@localhost") + arguments)
    private fun run(dir: Path, command: List<String>) {
        val process = ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().decodeToString()
        check(process.waitFor() == 0) { output }
    }
}
