package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.backend.NativeDiagnostics
import io.aequicor.magicpaper.backend.NativeResources
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlin.test.*

class PiInstallationLifecycleTest {
    private fun command(root: File, source: String): List<String> {
        val probe = root.resolve("InstallProbe.java").apply {
            writeText("class InstallProbe { public static void main(String[] args) throws Exception { $source } }")
        }
        val binary = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        return listOf(File(System.getProperty("java.home"), "bin/$binary").path, probe.path)
    }

    @Test fun cancellationStopsSilentInstallerAndKeepsCancellation() = runBlocking {
        val root = Files.createTempDirectory("pi-install-cancel-").toFile()
        try {
            val receipt = root.resolve("pid")
            val command = command(root, "java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv(\"PROBE_PID\")), Long.toString(ProcessHandle.current().pid())); Thread.sleep(60000);")
            val run = async { testInstallation(root).commandOutput(command, root, mapOf("PROBE_PID" to receipt.path), 60) }
            withTimeout(20_000) { while (!receipt.isFile || receipt.readText().isBlank()) delay(10) }
            val pid = receipt.readText().toLong()
            withTimeout(5_000) { run.cancelAndJoin() }
            assertTrue(run.isCancelled)
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        } finally { root.deleteRecursively() }
    }

    @Test fun unsuccessfulCommandDoesNotExposeRawOutputAsErrorMessage() = runBlocking {
        val root = Files.createTempDirectory("pi-install-exit-").toFile()
        try {
            val failure = assertFailsWith<IllegalStateException> {
                testInstallation(root).commandOutput(command(root, "System.out.print(\"private-fixture-content\"); System.exit(7);"), root, emptyMap(), 20)
            }
            assertContains(failure.message.orEmpty(), "code 7")
            assertFalse(failure.message.orEmpty().contains("private-fixture-content"))
        } finally { root.deleteRecursively() }
    }

    @Test fun installationDoesNotConvertCancellationIntoErrorStatus() = runBlocking<Unit> {
        val root = Files.createTempDirectory("pi-install-state-").toFile()
        try {
            root.resolve("prefix/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js").apply { parentFile.mkdirs(); writeText("") }
            val installation = PiNativeInstallation(root,
                NativeResources { throw CancellationException("fixture") },
                NativeDiagnostics { _, _, _, _ -> fail("Cancellation must not be logged as operational failure") })
            assertFailsWith<CancellationException> { installation.ensureReady().toList() }
        } finally { root.deleteRecursively() }
    }
}
