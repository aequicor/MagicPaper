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
 * Seatbelt used to refuse `posix_spawn`, so `python3` and `gradlew` checks failed at once and the agent, unable to fix
 * the sandbox, blocked its task. Program start is allowed now and the stop proof follows the profile instead of the
 * group. The user's grant is kept in reserve for a sandbox that refuses program start; the fixture below is one.
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
        override fun spawnRefused(output: String) = "Operation not permitted" in output
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

    @Test fun seatbeltProfileAllowsProgramStartAndMarksItsProcesses() {
        val policy = ResearchWorkspacePolicy(Path.of("/p"), emptyList(), emptyList(), emptyList())
        for (profile in listOf(MacResearchSandbox.profile(null, SeatbeltMembership()), MacResearchSandbox.profile(policy, SeatbeltMembership()))) {
            assertFalse("SYS_posix_spawn" in profile, profile)
            assertContains(profile, "(deny syscall-unix (syscall-number SYS_setpgid SYS_setsid))")
            assertContains(profile, "(deny signal)")
        }
        val membership = SeatbeltMembership()
        assertContains(MacResearchSandbox.profile(null, membership), membership.rule)
        assertNotEquals(membership.marker, SeatbeltMembership().marker, "each check marks its own processes")
    }

    @Test fun noSandboxReportsAProgramStartRefusalSoAFailingBuildAsksNobody() {
        val failures = listOf("xargs: /bin/echo: Operation not permitted",
            "exec failed: Error Domain=NSPOSIXErrorDomain Code=1 \"Operation not permitted\"",
            "python3: posix_spawn: /Library/Developer/CommandLineTools/Library/Frameworks/Python3.framework: Undefined error: 0")
        for (sandbox in listOf(MacResearchSandbox, LinuxResearchSandbox, WindowsResearchSandbox)) failures.forEach {
            assertFalse(sandbox.spawnRefused(it), "$sandbox: $it")
        }
    }

    private val nativeMac get() = System.getProperty("magicpaper.research.native") == "true" && System.getProperty("os.name").startsWith("Mac")

    /** Run with -Pmagicpaper.research.native=true on macOS: the real Seatbelt starts `xargs` and Python without a grant. */
    @Test fun nativeSeatbeltStartsProgramsWithoutAGrant() {
        assumeTrue(nativeMac)
        val command = listOf("/bin/sh", "-c", "echo SPAWNED | xargs /bin/echo")
        val result = run(ResearchSandbox.current(), spawnGranted = false, arguments = command)
        assertEquals(0, result.exitCode, result.toString())
        assertContains(result.output, "SPAWNED")
        assertFalse(result.spawnRefused)
        assumeTrue("python3 is installed", pythonAvailable())
        val python = run(ResearchSandbox.current(), spawnGranted = false, arguments = listOf("/usr/bin/python3", "-c", "print('PYTHON_RAN')"))
        assertEquals(0, python.exitCode, python.toString())
        assertContains(python.output, "PYTHON_RAN")
    }

    /**
     * A descendant started into its own session through `posix_spawn` leaves the process group; the stop proof still
     * finds it by the profile and stops it before the check completes, so it never writes afterwards.
     */
    @Test fun nativeSeatbeltStopsADescendantThatLeftTheGroup() = runBlocking {
        assumeTrue(nativeMac && pythonAvailable())
        val root = Files.createTempDirectory("spawn-escape-")
        try {
            val project = Files.createDirectory(root.resolve("project"))
            val late = project.resolve("late.txt")
            val owner = DefaultCommandChecks(InMemoryEventJournal(), InMemoryKeyValueStore(),
                SandboxCheckDriver(root.resolve("checks"), 30_000) { ResearchSandbox.current() })
            val escape = "import os\n" +
                "child = os.posix_spawn('/bin/sh', ['sh', '-c', 'sleep 2; printf late > late.txt'], dict(os.environ), setsid=True)\n" +
                "print('LEFT' if os.getpgid(child) != os.getpgid(0) else 'STAYED', flush=True)"
            val result = owner.run(CheckCommand(CheckRef(CheckScope("p", "s", UUID.randomUUID().toString(), 0), "check"),
                project.toString(), listOf("/usr/bin/python3", "-c", escape), policy = CheckPolicy.MANAGED_WORKTREE))
            assertEquals(0, result.exitCode, result.toString())
            assertContains(result.output, "LEFT", message = "the fixture must really leave the group")
            Thread.sleep(3_000)
            assertFalse(Files.exists(late), "a descendant outside the group outlived the check")
        } finally { root.toFile().deleteRecursively() }
    }

    private fun pythonAvailable(): Boolean = try {
        ProcessBuilder("/usr/bin/python3", "-c", "pass").redirectErrorStream(true).start().let {
            it.inputStream.readAllBytes(); it.waitFor(30, TimeUnit.SECONDS) && it.exitValue() == 0
        }
    } catch (_: java.io.IOException) { false }
}
