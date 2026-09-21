package io.aequicor.magicpaper.data.research

import io.aequicor.magicpaper.data.checks.DefaultCommandChecks
import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.domain.checks.*
import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.*

class BinaryCommandChecksTest {
    private class Native : ResearchSandbox {
        var prepares = 0
        var releases = 0
        var stops = 0
        var failCleanup = false
        var awaitCancellation = false
        var oversize = false
        var receivedEnvironment: Map<String, String> = emptyMap()
        val released = CompletableDeferred<Unit>()
        val bytes = byteArrayOf(0, 1, 10, 13, 0xFF.toByte(), 0xC0.toByte(), 0x80.toByte(), 0)

        override fun prepare(command: List<String>, cwd: Path, environment: Map<String, String>, policy: ResearchWorkspacePolicy?,
            receiptId: String, receiptDirectory: Path, authorityRecorder: CheckAuthorityRecorder): PreparedCheckProcess =
            error("Binary stdout must not use the combined text transport")

        override fun prepareBinary(command: List<String>, cwd: Path, environment: Map<String, String>, policy: ResearchWorkspacePolicy?,
            receiptId: String, receiptDirectory: Path, standardOutput: Path,
            authorityRecorder: CheckAuthorityRecorder): PreparedCheckProcess {
            prepares++
            receivedEnvironment = environment
            assertNull(policy)
            return object : PreparedCheckProcess() {
                @Volatile var alive = true
                override val receipt = CheckProcessReceipt(receiptId, "controlled-group", 123)
                override fun release() {
                    releases++
                    if (oversize) RandomAccessFile(standardOutput.toFile(), "rw").use { it.setLength(BinaryCheckOutputs.LIMIT + 1) }
                    else Files.write(standardOutput, bytes)
                    if (!awaitCancellation) alive = false
                    released.complete(Unit)
                }
                override suspend fun stopAndConfirm(): NativeCheckCleanup {
                    stops++
                    if (failCleanup) throw IOException("controlled missing group proof")
                    alive = false
                    return NativeCheckCleanup("group-stopped", "authority-restored")
                }
                override fun getInputStream(): InputStream = "stderr diagnostic".byteInputStream()
                override fun getErrorStream(): InputStream = InputStream.nullInputStream()
                override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
                override fun waitFor() = 0
                override fun waitFor(timeout: Long, unit: TimeUnit) = !alive
                override fun exitValue(): Int { check(!alive); return 0 }
                override fun isAlive() = alive
                override fun destroy() = Unit
            }
        }
    }

    private class Fixture {
        val root = Files.createTempDirectory("binary-checks-").toRealPath()
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val native = Native()
        val events = InMemoryEventJournal()
        val payloads = InMemoryKeyValueStore()
        val driver = SandboxCheckDriver(root.resolve("checks"), 10_000) { native }
        val owner = DefaultCommandChecks(events, payloads, driver)
        val executable = Paths.get(System.getProperty("java.home"), "bin",
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString()
        val command = CheckCommand(CheckRef(CheckScope("project", "session", "request", 0), "git-command"),
            workspace.toString(), listOf(executable), policy = CheckPolicy.MANAGED_WORKTREE,
            outputMode = CheckOutputMode.BINARY_STDOUT,
            environment = mapOf("GIT_INDEX_FILE" to workspace.resolve("isolated-index").toString(), "GIT_TERMINAL_PROMPT" to "0"))

        suspend fun dispose() {
            native.failCleanup = false
            driver.cleanup()
            root.toFile().deleteRecursively()
        }
    }

    @Test fun binaryStdoutRemainsExactAndSeparateFromStderrAcrossReplay() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val result = f.owner.run(f.command)
            assertEquals(0, result.exitCode)
            assertEquals("stderr diagnostic", result.output)
            assertEquals(f.native.bytes.size.toLong(), assertNotNull(result.binaryOutput).bytes)
            assertContentEquals(f.native.bytes, f.owner.readOutput(f.command.ref))
            assertEquals(f.command.environment["GIT_INDEX_FILE"], f.native.receivedEnvironment["GIT_INDEX_FILE"])
            assertEquals("0", f.native.receivedEnvironment["GIT_TERMINAL_PROMPT"])
            val reopened = DefaultCommandChecks(f.events, f.payloads, f.driver)
            assertEquals(result, reopened.run(f.command))
            assertContentEquals(f.native.bytes, reopened.readOutput(f.command.ref))
            assertEquals(1, f.native.prepares)
            assertEquals(1, f.native.releases)
            assertEquals(1, f.native.stops)
            assertFalse(Files.list(f.root.resolve("checks")).use { paths -> paths.anyMatch { it.fileName.toString().startsWith("run-") } })
        } finally { f.dispose() }
    }

    @Test fun missingGroupProofCannotPublishBinaryOutputOrAdmitAnotherWriter() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.native.failCleanup = true
            assertFailsWith<CheckOutcomeUnknown> { f.owner.run(f.command) }
            assertFailsWith<CheckOutcomeUnknown> { f.owner.readOutput(f.command.ref) }
            assertFailsWith<io.aequicor.magicpaper.data.checks.CheckRejected> {
                f.owner.run(f.command.copy(ref = f.command.ref.copy(callId = "next")))
            }
            assertFalse(Files.exists(f.root.resolve("checks/outputs")))
            assertEquals(1, f.native.releases)
        } finally { f.dispose() }
    }

    @Test fun oversizeBinaryOutputFailsExplicitlyAfterConfirmedCleanupWithoutTruncation() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.native.oversize = true
            val result = f.owner.run(f.command)
            assertNull(result.exitCode)
            assertEquals("Вывод команды превышает допустимый размер", result.blockedReason)
            assertNull(result.binaryOutput)
            assertEquals(1, f.native.stops)
            assertEquals(result, f.owner.inspect(f.command.ref))
            assertFalse(Files.exists(f.root.resolve("checks/outputs")))
            f.native.oversize = false
            assertEquals(0, f.owner.run(f.command.copy(ref = f.command.ref.copy(callId = "fresh"))).exitCode)
        } finally { f.dispose() }
    }

    @Test fun cancellationKeepsItsControlFlowAndDoesNotPublishPartialBinaryOutput() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.native.awaitCancellation = true
            val running = async { f.owner.run(f.command) }
            f.native.released.await()
            running.cancel(CancellationException("controlled cancellation"))
            assertFailsWith<CancellationException> { running.await() }
            running.join()
            assertEquals(1, f.native.stops)
            assertNull(assertNotNull(f.owner.inspect(f.command.ref)).binaryOutput)
            assertFalse(Files.exists(f.root.resolve("checks/outputs")))
        } finally { f.dispose() }
    }

    @Test fun corruptArtifactFailsSafelyAfterReplayWithoutRepeatingTheProcess() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.owner.run(f.command)
            val output = Files.list(f.root.resolve("checks/outputs")).use { it.findFirst().orElseThrow() }
            Files.write(output, byteArrayOf(1, 2))
            val reopened = DefaultCommandChecks(f.events, f.payloads, f.driver)
            val failure = assertFailsWith<CheckOutcomeUnknown> { reopened.readOutput(f.command.ref) }
            assertFalse(failure.message.orEmpty().contains(output.toString()))
            assertEquals(1, f.native.prepares)
            assertEquals(1, f.native.releases)
            assertEquals(1, f.native.stops)
        } finally { f.dispose() }
    }
}
