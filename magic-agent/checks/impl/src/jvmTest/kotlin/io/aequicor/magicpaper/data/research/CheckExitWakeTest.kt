package io.aequicor.magicpaper.data.research

import io.aequicor.magicpaper.data.checks.CheckAuthorityRecorder
import io.aequicor.magicpaper.domain.checks.*
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.*

/**
 * A command is observed finished at its exit, not at the next output sample. Waiting out a fixed 50 ms
 * sample was about 40 ms of a ~90 ms read-only Git query, and a worktree availability check makes four.
 */
class CheckExitWakeTest {
    @Test fun exitEndsTheWaitWithoutWaitingOutTheSamplePeriod() = runBlocking {
        val root = Files.createTempDirectory("check-exit-wake-")
        val workspace = Files.createDirectory(root.resolve("project"))
        val exit = CompletableFuture<Int>()
        val sampled = CompletableDeferred<Unit>()
        val sandbox = object : ResearchSandbox {
            override fun prepare(command: List<String>, cwd: Path, environment: Map<String, String>, policy: ResearchWorkspacePolicy?,
                receiptId: String, receiptDirectory: Path, authorityRecorder: io.aequicor.magicpaper.data.research.CheckAuthorityRecorder) =
                object : PreparedCheckProcess() {
                    override val receipt = CheckProcessReceipt(receiptId, "fixture", 123)
                    override fun release() = Unit
                    override suspend fun stopAndConfirm(): NativeCheckCleanup { exit.complete(0); return NativeCheckCleanup("group", "authority") }
                    override fun getInputStream(): InputStream = InputStream.nullInputStream()
                    override fun getErrorStream(): InputStream = InputStream.nullInputStream()
                    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
                    override fun waitFor(): Int = exit.get()
                    override fun waitFor(timeout: Long, unit: TimeUnit) = try { exit.get(timeout, unit); true } catch (_: TimeoutException) { false }
                    override fun exitValue(): Int = exit.getNow(null) ?: throw IllegalThreadStateException("Process is alive")
                    // Only the owner's wait samples liveness here, so the first sample marks the wait as entered.
                    override fun isAlive() = !exit.isDone.also { done -> if (!done) sampled.complete(Unit) }
                    override fun onExit(): CompletableFuture<Process> = exit.handle { _, _ -> this }
                    override fun destroy() = Unit
                }
        }
        // A sample period far beyond this test's patience: a wait that sleeps it out cannot pass.
        val driver = SandboxCheckDriver(root.resolve("checks"), 120_000, sampleMillis = 600_000) { sandbox }
        try {
            val executable = Paths.get(System.getProperty("java.home"), "bin",
                if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString()
            val prepared = driver.prepare(CheckCommand(CheckRef(CheckScope("p", "s", "r", 0), "call"), workspace.toString(),
                listOf(executable), policy = CheckPolicy.MANAGED_WORKTREE), "receipt", CheckAuthorityRecorder { _, _ -> error("Unexpected ACL") })
            prepared.release()
            val result = async(Dispatchers.Default) { prepared.awaitResult { } }
            sampled.await()
            exit.complete(0)
            assertEquals(0, withTimeout(10_000) { result.await() }.exitCode)
        } finally { driver.cleanup(); root.toFile().deleteRecursively() }
    }
}
