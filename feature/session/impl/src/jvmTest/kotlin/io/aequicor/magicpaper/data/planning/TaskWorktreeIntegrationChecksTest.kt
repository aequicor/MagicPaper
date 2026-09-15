package io.aequicor.magicpaper.data.planning

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TaskWorktreeIntegrationChecksTest {
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
        val result = TaskWorktreeIntegrationChecks().run(dir.path, "write", shell(write))
        assertEquals(0, result.exitCode)
        assertNull(result.blockedReason)
        assertTrue(result.output.contains("done"), result.output)
        assertTrue(File(dir, "marker.txt").isFile, "Проверке задачи разрешена запись в управляемую копию")
        val failed = TaskWorktreeIntegrationChecks().run(dir.path, "fail", shell("exit 7"))
        assertEquals(7, failed.exitCode)
        assertNull(failed.blockedReason)
    } }

    @Test fun checkSeesInheritedEnvironment() = runTest { withDir("magicpaper-task-check-env-") { dir ->
        val result = TaskWorktreeIntegrationChecks().run(dir.path, "env", shell(if (windows) "echo %PATH%" else "echo \$PATH"))
        assertEquals(0, result.exitCode)
        val path = result.output.trim()
        assertTrue(path.isNotEmpty() && !path.contains("%PATH%"), path)
    } }

    // Процессы живут в реальном времени: виртуальные часы runTest сделали бы пределы и остановки нефизичными.
    @Test fun timedOutCheckIsBlockedInsteadOfSilentlyPassing() = runBlocking { withDir("magicpaper-task-check-timeout-") { dir ->
        val started = System.currentTimeMillis()
        val result = TaskWorktreeIntegrationChecks(timeoutMillis = 500).run(dir.path, "slow", sleep(30))
        assertTrue(System.currentTimeMillis() - started < 30_000)
        assertNull(result.exitCode)
        assertEquals(TaskWorktreeIntegrationChecks.TIMEOUT_NOTE, result.blockedReason)
    } }

    @Test fun abortStopsRunningCheck() = runBlocking { withDir("magicpaper-task-check-abort-") { dir ->
        val checks = TaskWorktreeIntegrationChecks(timeoutMillis = 60_000)
        val job = async(Dispatchers.IO) { checks.run(dir.path, "abortable", sleep(30)) }
        try {
            withTimeout(15_000) { while (job.isActive) { checks.abort("abortable"); delay(100) } }
            val result = job.await()
            assertTrue(result.exitCode != 0 || result.blockedReason != null, "Остановленная проверка не может быть успешной")
        } finally {
            checks.abort("abortable")
            withContext(NonCancellable) { withTimeoutOrNull(5_000) { job.join() } }
        }
    } }

    @Test fun cancellationStopsCheckAndPropagates() = runBlocking { withDir("magicpaper-task-check-cancel-") { dir ->
        val checks = TaskWorktreeIntegrationChecks(timeoutMillis = 60_000)
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
        val checks = TaskWorktreeIntegrationChecks()
        assertFailsWith<IllegalArgumentException> { checks.run(dir.path, "empty", emptyList()) }
        assertFailsWith<IllegalArgumentException> { checks.run(dir.path, "nul", listOf("git", "ver\u0000sion")) }
        assertFailsWith<IllegalArgumentException> { checks.run(File(dir, "missing").path, "dir", listOf("git", "--version")) }
    } }
}
