package io.aequicor.magicpaper.data.research

import io.aequicor.magicpaper.data.checks.CheckAuthorityRecorder
import io.aequicor.magicpaper.domain.checks.*
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.logging.AppLogEntry
import io.aequicor.magicpaper.logging.LogLevel
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * A verification failure used to name only an exit code. How the command ran is part of the answer: how its program was
 * found, whether it started directly or through an interpreter, in which sandbox, under which policy and for how long.
 */
class SandboxCheckLaunchLogTest {
    private val sandbox = object : ResearchSandbox {
        override fun launchMethod(program: String) = if (program.endsWith("java")) "direct" else "interpreter"
        override fun prepare(command: List<String>, cwd: Path, environment: Map<String, String>, policy: ResearchWorkspacePolicy?,
            receiptId: String, receiptDirectory: Path, authorityRecorder: io.aequicor.magicpaper.data.research.CheckAuthorityRecorder) =
            object : PreparedCheckProcess() {
                override val receipt = CheckProcessReceipt(receiptId, "fixture-group", 123)
                override fun release() = Unit
                override suspend fun stopAndConfirm() = NativeCheckCleanup("group", "authority")
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

    private fun prepared(level: LogLevel, arguments: List<String>): List<AppLogEntry> = runBlocking {
        val root = Files.createTempDirectory("check-launch-log-")
        val workspace = Files.createDirectory(root.resolve("project"))
        val driver = SandboxCheckDriver(root.resolve("checks"), 1234) { sandbox }
        val previous = AppLog.level
        val before = AppLog.history().lastOrNull()
        try {
            AppLog.level = level
            val check = driver.prepare(CheckCommand(CheckRef(CheckScope("p", "s", "operation", 0), "call"), workspace.toString(),
                arguments, policy = CheckPolicy.MANAGED_WORKTREE), "receipt", CheckAuthorityRecorder { _, _ -> error("Unexpected ACL") })
            check.stopAndConfirm(); check.discard()
            AppLog.history(after = before).filter { it.component == "checks" && it.event.startsWith("run.prepared") }
        } finally { AppLog.level = previous; driver.cleanup(); root.toFile().deleteRecursively() }
    }

    private val java = Paths.get(System.getProperty("java.home"), "bin",
        if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString()

    @Test fun agentCheckRecordsHowItRunsAtInfoAndItsPathsOnlyAtTrace() {
        val info = prepared(LogLevel.INFO, listOf(java, "-version")).single()
        assertEquals(LogLevel.INFO, info.level)
        assertEquals(java.substringAfterLast('/').substringAfterLast('\\'), info.fields["executable"])
        assertEquals("absolute", info.fields["strategy"])
        assertEquals("direct", info.fields["action"])
        assertEquals("fixture-group", info.fields["kind"])
        assertEquals("MANAGED_WORKTREE", info.fields["mode"])
        assertEquals("TEXT", info.fields["format"])
        assertEquals("1234", info.fields["timeoutMs"])
        assertEquals("1", info.fields["argumentCount"])
        assertTrue(info.fields.getValue("requestId").startsWith("id-"), "correlates with the worktree operation's id")
        assertFalse(System.getProperty("java.home") in info.line(), "directories stay out of INFO")
        val detail = prepared(LogLevel.TRACE, listOf(java, "-version")).single { it.level == LogLevel.TRACE }
        assertContains(detail.detail.orEmpty(), "executable=")
        assertContains(detail.detail.orEmpty(), "cwd=")
    }

    @Test fun applicationGitPlumbingStaysAtDebug() {
        assertTrue(prepared(LogLevel.INFO, listOf("git", "--version")).isEmpty(), "Git plumbing does not flood INFO")
        val debug = prepared(LogLevel.DEBUG, listOf("git", "--version")).single()
        assertEquals(LogLevel.DEBUG, debug.level)
        assertEquals("path", debug.fields["strategy"])
    }
}
