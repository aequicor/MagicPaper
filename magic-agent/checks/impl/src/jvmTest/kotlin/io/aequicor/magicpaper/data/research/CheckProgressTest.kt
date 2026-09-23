package io.aequicor.magicpaper.data.research

import io.aequicor.magicpaper.data.checks.CheckAuthorityRecorder
import io.aequicor.magicpaper.domain.checks.*
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.*

/**
 * Output a command has written reaches progress while the command runs, even before its line ends. Since
 * per-line decoding arrived, `printf STARTED` stayed invisible until exit: whoever waited for it to stop the
 * command stopped one that had already finished, and the native stop tests failed for that reason alone.
 */
class CheckProgressTest {
    @Test fun unfinishedLineReachesProgressWhileTheCommandRuns() = runBlocking {
        val root = Files.createTempDirectory("check-progress-")
        val workspace = Files.createDirectory(root.resolve("project"))
        val written = PipedOutputStream()
        val output = PipedInputStream(written)
        val exit = CompletableFuture<Int>()
        val sandbox = object : ResearchSandbox {
            override fun prepare(command: List<String>, cwd: Path, environment: Map<String, String>, policy: ResearchWorkspacePolicy?,
                receiptId: String, receiptDirectory: Path, authorityRecorder: io.aequicor.magicpaper.data.research.CheckAuthorityRecorder) =
                object : PreparedCheckProcess() {
                    override val receipt = CheckProcessReceipt(receiptId, "fixture", 123)
                    override fun release() = Unit
                    override suspend fun stopAndConfirm(): NativeCheckCleanup {
                        exit.complete(0); written.close()
                        return NativeCheckCleanup("group", "authority")
                    }
                    override fun getInputStream(): InputStream = output
                    override fun getErrorStream(): InputStream = InputStream.nullInputStream()
                    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
                    override fun waitFor(): Int = exit.get()
                    override fun waitFor(timeout: Long, unit: TimeUnit) = try { exit.get(timeout, unit); true } catch (_: TimeoutException) { false }
                    override fun exitValue(): Int = exit.getNow(null) ?: throw IllegalThreadStateException("Process is alive")
                    override fun isAlive() = !exit.isDone
                    override fun onExit(): CompletableFuture<Process> = exit.handle { _, _ -> this }
                    override fun destroy() = Unit
                }
        }
        val driver = SandboxCheckDriver(root.resolve("checks"), 120_000) { sandbox }
        try {
            val executable = Paths.get(System.getProperty("java.home"), "bin",
                if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString()
            val prepared = driver.prepare(CheckCommand(CheckRef(CheckScope("p", "s", "r", 0), "call"), workspace.toString(),
                listOf(executable), policy = CheckPolicy.MANAGED_WORKTREE), "receipt", CheckAuthorityRecorder { _, _ -> error("Unexpected ACL") })
            prepared.release()
            val shown = CompletableDeferred<String>()
            val result = async(Dispatchers.Default) { prepared.awaitResult { if ("STARTED" in it) shown.complete(it) } }
            written.write("STARTED".toByteArray()); written.flush()
            assertEquals("STARTED", withTimeout(10_000) { shown.await() }, "an unfinished line must be visible while the command runs")
            assertTrue(!exit.isDone)
            written.close(); exit.complete(0)
            assertEquals("STARTED", withTimeout(10_000) { result.await() }.output, "the final output is unchanged")
        } finally { written.close(); exit.complete(0); driver.cleanup(); root.toFile().deleteRecursively() }
    }
}
