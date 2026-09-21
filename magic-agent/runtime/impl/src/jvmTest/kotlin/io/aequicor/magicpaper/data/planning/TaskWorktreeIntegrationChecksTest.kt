package io.aequicor.magicpaper.data.planning

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TaskWorktreeIntegrationChecksTest {
    private val checkOwners = mutableListOf<Pair<io.aequicor.magicpaper.domain.checks.CommandChecks, java.nio.file.Path>>()
    private fun newChecks(timeoutMillis: Long = TaskWorktreeIntegrationChecks.DEFAULT_TIMEOUT_MILLIS): TaskWorktreeIntegrationChecks {
        val root = Files.createTempDirectory("task-command-owner-")
        val owner = io.aequicor.magicpaper.data.checks.createCommandChecks(
            io.aequicor.magicpaper.data.storage.InMemoryEventJournal(), io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore(), root, timeoutMillis)
        checkOwners += owner to root
        return TaskWorktreeIntegrationChecks(owner)
    }
    @AfterTest fun closeOwners() = runBlocking {
        checkOwners.forEach { (owner, root) -> owner.close(); check(root.toFile().deleteRecursively()) }
    }
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private fun shell(script: String) = if (windows) listOf("cmd.exe", "/c", script) else listOf("/bin/sh", "-c", script)
    private fun sleep(seconds: Int) = if (windows) shell("ping -n ${seconds + 1} 127.0.0.1 >nul") else shell("sleep $seconds")
    private fun tempDir(prefix: String) = Files.createTempDirectory(prefix).toFile()
    private suspend fun <T> withDir(prefix: String, block: suspend (File) -> T): T {
        val dir = tempDir(prefix)
        try { return block(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun checkWritesIntoManagedCopyAndReportsOutputAndCode() = runTest { withDir("magicpaper-task-check-") { dir ->
        val write = if (windows) "echo done> marker.txt & type marker.txt" else "echo done > marker.txt && cat marker.txt"
        val result = newChecks().run(dir.path, "write", shell(write))
        assertEquals(0, result.exitCode)
        assertNull(result.blockedReason)
        assertTrue(result.output.contains("done"), result.output)
        assertTrue(File(dir, "marker.txt").isFile, "Проверке задачи разрешена запись в управляемую копию")
        val failed = newChecks().run(dir.path, "fail", shell("exit 7"))
        assertEquals(7, failed.exitCode)
        assertNull(failed.blockedReason)
    } }

    @Test fun checkSeesInheritedEnvironment() = runTest { withDir("magicpaper-task-check-env-") { dir ->
        val result = newChecks().run(dir.path, "env", shell(if (windows) "echo %PATH%" else "echo \$PATH"))
        assertEquals(0, result.exitCode)
        val path = result.output.trim()
        assertTrue(path.isNotEmpty() && !path.contains("%PATH%"), path)
    } }

    @Test fun relativeExecutableIsResolvedAgainstWorktree() = runTest { withDir("magicpaper-task-check-relative-") { dir ->
        val file = if (windows) File(dir, "run.cmd") else File(dir, "run.sh")
        file.writeText(if (windows) "@echo from-worktree" else "#!/bin/sh\necho from-worktree")
        if (!windows) file.setExecutable(true)
        val result = newChecks().run(dir.path, "relative", listOf("./" + file.name))
        assertEquals(0, result.exitCode, result.output)
        assertTrue(result.output.contains("from-worktree"), result.output)
    } }

    /** Регресс CreateProcess error=193: POSIX-обёртка `./gradlew` не должна запускаться на Windows вместо `gradlew.bat`. */
    @Test fun windowsPrefersExecutableWrapperOverPosixScript() = runTest { withDir("magicpaper-task-check-wrapper-") { dir ->
        File(dir, "gradlew").writeText("#!/bin/sh\necho from-posix-wrapper")
        if (windows) {
            File(dir, "gradlew.bat").writeText("@echo from-windows-wrapper")
            val result = newChecks().run(dir.path, "wrapper", listOf("./gradlew", "help"))
            assertEquals(0, result.exitCode, result.output + result.blockedReason)
            assertTrue(result.output.contains("from-windows-wrapper"), result.output)
        } else {
            File(dir, "gradlew").setExecutable(true)
            val result = newChecks().run(dir.path, "wrapper", listOf("./gradlew"))
            assertEquals(0, result.exitCode, result.output)
            assertTrue(result.output.contains("from-posix-wrapper"), result.output)
        }
    } }

    @Test fun nonExecutableScriptOnWindowsReportsActionableReason() = runTest { withDir("magicpaper-task-check-posix-only-") { dir ->
        File(dir, "gradlew").writeText("#!/bin/sh\necho from-posix-wrapper")
        if (windows) {
            val result = newChecks().run(dir.path, "posix-only", listOf("./gradlew"))
            assertNull(result.exitCode)
            assertNotNull(result.blockedReason)
            assertTrue(result.blockedReason!!.contains("gradlew"), result.blockedReason)
            assertFalse(result.blockedReason!!.contains("error=193"), result.blockedReason)
        } else {
            File(dir, "gradlew").setExecutable(true)
            assertEquals(0, newChecks().run(dir.path, "posix-only", listOf("./gradlew")).exitCode)
        }
    } }

    // Процессы живут в реальном времени: виртуальные часы runTest сделали бы пределы и остановки нефизичными.
    @Test fun timedOutCheckIsBlockedInsteadOfSilentlyPassing() = runBlocking { withDir("magicpaper-task-check-timeout-") { dir ->
        val started = System.currentTimeMillis()
        val result = newChecks(timeoutMillis = 500).run(dir.path, "slow", sleep(30))
        assertTrue(System.currentTimeMillis() - started < 30_000)
        assertNull(result.exitCode)
        assertEquals(TaskWorktreeIntegrationChecks.TIMEOUT_NOTE, result.blockedReason)
    } }

    @Test fun abortStopsRunningCheck() = runBlocking { withDir("magicpaper-task-check-abort-") { dir ->
        val checks = newChecks(timeoutMillis = 60_000)
        val marker = File(dir, "started.txt")
        val command = if (windows) "echo started> started.txt & ping -n 31 127.0.0.1 >nul" else "echo started > started.txt; sleep 30"
        val job = async(Dispatchers.IO) { checks.run(dir.path, "abortable", shell(command)) }
        try {
            withTimeout(15_000) { while (!marker.exists()) { if (job.isCompleted) job.await(); delay(25) } }
            withTimeout(15_000) { while (job.isActive) { checks.abort("abortable"); delay(100) } }
            // The application owner cancels the exact invocation; it records a failed result only after group cleanup.
            assertFailsWith<CancellationException> { job.await() }
            checks.reconcile("abortable")
            val ref = io.aequicor.magicpaper.domain.checks.CheckRef(io.aequicor.magicpaper.domain.checks.CheckScope(
                checkWorkspaceId(dir.canonicalPath), "abortable", "abortable", 0), "abortable")
            val result = checkOwners.last().first.inspect(ref)
            assertNull(result?.exitCode)
            assertTrue(result?.blockedReason?.startsWith("Проверка отменена") == true)
        } finally {
            checks.abort("abortable")
            withContext(NonCancellable) { withTimeoutOrNull(5_000) { job.join() } }
        }
    } }

    @Test fun cancellationStopsCheckAndPropagates() = runBlocking { withDir("magicpaper-task-check-cancel-") { dir ->
        val checks = newChecks(timeoutMillis = 60_000)
        val marker = File(dir, "started.txt")
        val start = if (windows) "echo started> started.txt & ping -n 31 127.0.0.1 >nul" else "echo started > started.txt; sleep 30"
        val job = async(Dispatchers.IO) { checks.run(dir.path, "cancelled", shell(start)) }
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + 10_000
            while (!marker.isFile && System.currentTimeMillis() < deadline) Thread.sleep(50)
        }
        assertTrue(marker.isFile, "Проверка не стартовала")
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        Unit
    } }

    @Test fun invalidCommandsAreRefusedBeforeLaunch() = runTest { withDir("magicpaper-task-check-invalid-") { dir ->
        val checks = newChecks()
        assertFailsWith<IllegalArgumentException> { checks.run(dir.path, "empty", emptyList()) }
        assertFailsWith<IllegalArgumentException> { checks.run(dir.path, "nul", listOf("git", "ver\u0000sion")) }
        assertFailsWith<IllegalArgumentException> { checks.run(File(dir, "missing").path, "dir", listOf("git", "--version")) }
    } }
}
