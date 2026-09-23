package io.aequicor.magicpaper.data.research

import io.aequicor.magicpaper.data.checks.CheckAuthorityRecorder
import io.aequicor.magicpaper.domain.checks.*
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SandboxCheckHandoffTest {
    @Test fun cancellationBeforeTheBlockRunsIsNotDispatched() = runBlocking {
        val root = Files.createTempDirectory("check-handoff-entry-")
        val workspace = Files.createDirectory(root.resolve("project"))
        val driver = SandboxCheckDriver(root.resolve("checks"), 1000) { error("the native adapter must not be reached") }
        var thrown: Throwable? = null
        try {
            val task = async {
                // withContext checks cancellation on entry and then never runs its block.
                currentCoroutineContext().cancel()
                try {
                    driver.prepare(CheckCommand(CheckRef(CheckScope("p", "s", "r", 0), "call"), workspace.toString(),
                        listOf("git"), policy = CheckPolicy.MANAGED_WORKTREE), "receipt", CheckAuthorityRecorder { _, _ -> error("Unexpected ACL") })
                } catch (failure: Throwable) { thrown = failure; throw failure }
                Unit
            }
            assertFailsWith<CancellationException> { task.await() }
            val cancelled = assertIs<io.aequicor.magicpaper.data.checks.CheckPreparationCancelled>(thrown,
                "nothing was prepared, so the owner can record the call as not dispatched instead of unknown")
            assertNull(cancelled.restoredAuthority)
        } finally { driver.cleanup(); root.toFile().deleteRecursively() }
    }

    @Test fun promptCancellationAtDispatcherReturnCannotLosePreparedProcess() = runBlocking {
        val root = Files.createTempDirectory("check-handoff-")
        val workspace = Files.createDirectory(root.resolve("project"))
        var stops = 0
        var releases = 0
        var thrown: Throwable? = null
        lateinit var task: Deferred<Unit>
        val sandbox = object : ResearchSandbox {
            override fun prepare(command: List<String>, cwd: Path, environment: Map<String, String>, policy: ResearchWorkspacePolicy?,
                receiptId: String, receiptDirectory: Path, authorityRecorder: io.aequicor.magicpaper.data.research.CheckAuthorityRecorder): PreparedCheckProcess {
                task.cancel(CancellationException("cancel at native return"))
                return object : PreparedCheckProcess() {
                    override val receipt = CheckProcessReceipt(receiptId, "fixture", 123)
                    override fun release() { releases++ }
                    override suspend fun stopAndConfirm(): NativeCheckCleanup { stops++; return NativeCheckCleanup("group", "authority") }
                    override fun getInputStream(): InputStream = InputStream.nullInputStream()
                    override fun getErrorStream(): InputStream = InputStream.nullInputStream()
                    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
                    override fun waitFor() = 0
                    override fun waitFor(timeout: Long, unit: TimeUnit) = true
                    override fun exitValue() = 0
                    override fun isAlive() = false
                    override fun destroy() = Unit
                }
            }
        }
        val driver = SandboxCheckDriver(root.resolve("checks"), 1000) { sandbox }
        try {
            task = async(start = CoroutineStart.LAZY) {
                val executable = Paths.get(System.getProperty("java.home"), "bin",
                    if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString()
                // await() of a cancelled task reports the task's own cancellation, not what the driver threw.
                try {
                    driver.prepare(CheckCommand(CheckRef(CheckScope("p", "s", "r", 0), "call"), workspace.toString(),
                        listOf(executable), policy = CheckPolicy.MANAGED_WORKTREE), "receipt", CheckAuthorityRecorder { _, _ -> error("Unexpected ACL") })
                } catch (failure: Throwable) { thrown = failure; throw failure }
                Unit
            }
            task.start()
            assertFailsWith<CancellationException> { task.await() }
            assertEquals("authority", assertIs<io.aequicor.magicpaper.data.checks.CheckPreparationCancelled>(thrown).restoredAuthority,
                "a stopped, never released process reaches the owner as not dispatched, with its cleanup proof")
            assertEquals(0, releases)
            assertEquals(1, stops)
            driver.cleanup()
            assertEquals(1, stops, "The already discarded resource must not remain in the application owner")
        } finally { driver.cleanup(); root.toFile().deleteRecursively() }
    }
}
