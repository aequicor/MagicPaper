package io.aequicor.magicpaper.data.research

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.*

class NativeCheckProcessNativeTest {
    private val windows get() = System.getProperty("os.name").startsWith("Windows")
    private fun shell(unix: String, win: String): List<String> = if (windows)
        listOf(Path.of(System.getenv("SystemRoot"), "System32/WindowsPowerShell/v1.0/powershell.exe").toString(), "-NoProfile", "-NonInteractive", "-Command", win)
        else listOf("/bin/sh", "-c", unix)
    private fun prepare(root: Path, command: List<String>): PreparedCheckProcess = ResearchSandbox.current().prepare(
        command, root, System.getenv(), null, UUID.randomUUID().toString(), root.resolve("receipts")) { _, _ -> error("Managed check must not grant ACLs") }

    @Test fun durablePreparationCannotRunCommandUntilExplicitRelease() = runBlocking<Unit> {
        assumeTrue(System.getProperty("magicpaper.research.native") == "true")
        val root = Files.createTempDirectory("check-gated-").toRealPath()
        try {
            val process = prepare(root, shell("printf done > marker; printf OUTPUT", "Set-Content marker done; Write-Output OUTPUT"))
            try {
                Thread.sleep(150)
                assertFalse(Files.exists(root.resolve("marker")))
                assertNull(readNativeCheckCleanup(process.receipt, root.resolve("receipts")))
                assertTrue(Files.list(root.resolve("receipts")).use { it.anyMatch { path -> path.fileName.toString().endsWith(".json") } })
                process.release()
                assertFails { process.release() }
                val output = process.inputStream.readAllBytes().decodeToString()
                assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue())
                assertContains(output, "OUTPUT")
                assertTrue(Files.exists(root.resolve("marker")))
                assertEquals(process.stopAndConfirm(), readNativeCheckCleanup(process.receipt, root.resolve("receipts")))
            } finally { process.stopAndConfirm(); process.inputStream.close() }
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun cancellingPreparedCommandNeverExecutesItAndPersistsCleanup() = runBlocking<Unit> {
        assumeTrue(System.getProperty("magicpaper.research.native") == "true")
        val root = Files.createTempDirectory("check-unreleased-").toRealPath()
        try {
            val process = prepare(root, shell("printf wrong > marker", "Set-Content marker wrong"))
            val proof = process.stopAndConfirm()
            process.inputStream.close()
            assertFalse(Files.exists(root.resolve("marker")))
            assertEquals(proof, readNativeCheckCleanup(process.receipt, root.resolve("receipts")))
            assertFails { process.release() }
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun managedCommandCanWriteSourcesButItsChildCannotOutliveCompletion() = runBlocking<Unit> {
        assumeTrue(System.getProperty("magicpaper.research.native") == "true")
        val root = Files.createTempDirectory("check-managed-").toRealPath()
        try {
            val process = prepare(root, shell("printf changed > source; (sleep 2; printf late > late) & printf DONE",
                "Set-Content source changed; Start-Process powershell.exe -ArgumentList '-NoProfile','-Command','Start-Sleep 2; Set-Content late late'; Write-Output DONE"))
            try {
                process.release()
                assertContains(process.inputStream.readAllBytes().decodeToString(), "DONE")
                assertTrue(process.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, process.exitValue())
                process.stopAndConfirm()
                assertEquals("changed", Files.readString(root.resolve("source")).trim())
                Thread.sleep(2200)
                assertFalse(Files.exists(root.resolve("late")))
            } finally { process.stopAndConfirm(); process.inputStream.close() }
        } finally { root.toFile().deleteRecursively() }
    }
}
