package io.aequicor.magicpaper.data.research

import io.aequicor.magicpaper.data.checks.DefaultCommandChecks
import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.domain.checks.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * Seatbelt refuses `posix_spawn`, so `python3` and `gradlew` checks failed at once and the agent, unable to fix the
 * sandbox, blocked its task. The refusal is now named in the result, and a command the user allowed runs without it.
 */
class SandboxSpawnGrantTest {
    private class Sandbox(private val output: String, private val code: Int) : ResearchSandbox {
        val prepared = mutableListOf<String>()
        private fun process(receiptId: String) = object : PreparedCheckProcess() {
            override val receipt = CheckProcessReceipt(receiptId, "fixture-group", 123)
            private val bytes = output.byteInputStream()
            override fun release() = Unit
            override suspend fun stopAndConfirm() = NativeCheckCleanup("group", "authority")
            override fun getInputStream(): InputStream = bytes
            override fun getErrorStream(): InputStream = InputStream.nullInputStream()
            override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
            override fun waitFor() = code
            override fun waitFor(timeout: Long, unit: TimeUnit) = true
            override fun exitValue() = code
            override fun isAlive() = false
            override fun destroy() = Unit
        }
        override fun prepare(command: List<String>, cwd: Path, environment: Map<String, String>, policy: ResearchWorkspacePolicy?,
            receiptId: String, receiptDirectory: Path, authorityRecorder: CheckAuthorityRecorder): PreparedCheckProcess {
            prepared += "contained"; return process(receiptId)
        }
        override fun prepareSpawning(command: List<String>, cwd: Path, environment: Map<String, String>,
            receiptId: String, receiptDirectory: Path, authorityRecorder: CheckAuthorityRecorder): PreparedCheckProcess {
            prepared += "spawning"; return process(receiptId)
        }
        override fun spawnRefused(output: String) = MacResearchSandbox.spawnRefused(output)
    }

    private fun run(sandbox: ResearchSandbox, spawnGranted: Boolean,
        arguments: List<String> = listOf("/bin/sh", "-c", "true")): CheckResult = runBlocking {
        val root = Files.createTempDirectory("spawn-grant-")
        try {
            val project = Files.createDirectory(root.resolve("project"))
            val owner = DefaultCommandChecks(InMemoryEventJournal(), InMemoryKeyValueStore(), SandboxCheckDriver(root.resolve("checks"), 30_000) { sandbox })
            owner.run(CheckCommand(CheckRef(CheckScope("p", "s", UUID.randomUUID().toString(), 0), "check"), project.toString(),
                arguments, policy = CheckPolicy.MANAGED_WORKTREE, spawnGranted = spawnGranted))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun refusedProgramStartIsNamedSoTheUserNotTheAgentDecides() {
        val sandbox = Sandbox("xargs: /bin/echo: Operation not permitted\n", 1)
        val result = run(sandbox, spawnGranted = false)
        assertEquals(1, result.exitCode)
        assertTrue(result.spawnRefused, result.toString())
        assertEquals(listOf("contained"), sandbox.prepared)
    }

    @Test fun grantedCommandRunsOutsideTheSpawnRefusalAndItsFailureIsOrdinary() {
        val sandbox = Sandbox("xargs: /bin/echo: Operation not permitted\n", 1)
        val result = run(sandbox, spawnGranted = true)
        assertEquals(listOf("spawning"), sandbox.prepared)
        assertFalse(result.spawnRefused, "a granted command has nothing left to ask for")
    }

    @Test fun ordinaryFailureAndSuccessAreNotRefusals() {
        assertFalse(run(Sandbox("FAILURE: Build failed with an exception.\n", 1), spawnGranted = false).spawnRefused)
        assertFalse(run(Sandbox("Operation not permitted\n", 0), spawnGranted = false).spawnRefused, "a passed check is never a refusal")
    }

    @Test fun seatbeltProfileLiftsOnlyTheSpawnRefusalForAGrantedCommand() {
        val contained = MacResearchSandbox.profile(null)
        assertContains(contained, "SYS_setpgid SYS_setsid SYS_posix_spawn")
        val granted = MacResearchSandbox.profile(null, spawnGranted = true)
        assertFalse("SYS_posix_spawn" in granted, granted)
        assertContains(granted, "(deny syscall-unix (syscall-number SYS_setpgid SYS_setsid))")
        assertContains(granted, "(deny signal)")
        assertFailsWith<IllegalArgumentException> {
            MacResearchSandbox.profile(ResearchWorkspacePolicy(Path.of("/p"), emptyList(), emptyList(), emptyList()), spawnGranted = true)
        }
    }

    @Test fun seatbeltRefusalIsRecognisedFromEachLauncherThatHitsIt() {
        assertTrue(MacResearchSandbox.spawnRefused("python3: posix_spawn: /Library/Developer/CommandLineTools/Library/Frameworks/" +
            "Python3.framework/Versions/3.9/Resources/Python.app/Contents/MacOS/Python: Undefined error: 0"))
        assertTrue(MacResearchSandbox.spawnRefused("exec failed: Error Domain=NSPOSIXErrorDomain Code=1 \"Operation not permitted\""))
        assertFalse(MacResearchSandbox.spawnRefused("> Task :app:jvmTest FAILED\n3 tests completed, 1 failed"))
        assertFalse(LinuxResearchSandbox.spawnRefused("xargs: /bin/echo: Operation not permitted"), "bubblewrap does not refuse program start")
    }

    /** Run with -Pmagicpaper.research.native=true on macOS: the real Seatbelt refuses and then admits `xargs`. */
    @Test fun nativeSeatbeltRefusesProgramStartUntilGranted() {
        assumeTrue(System.getProperty("magicpaper.research.native") == "true" && System.getProperty("os.name").startsWith("Mac"))
        val command = listOf("/bin/sh", "-c", "echo SPAWNED | xargs /bin/echo")
        val refused = run(ResearchSandbox.current(), spawnGranted = false, arguments = command)
        assertNotEquals(0, refused.exitCode, refused.toString())
        assertTrue(refused.spawnRefused, refused.toString())
        val granted = run(ResearchSandbox.current(), spawnGranted = true, arguments = command)
        assertEquals(0, granted.exitCode, granted.toString())
        assertContains(granted.output, "SPAWNED")
    }
}
