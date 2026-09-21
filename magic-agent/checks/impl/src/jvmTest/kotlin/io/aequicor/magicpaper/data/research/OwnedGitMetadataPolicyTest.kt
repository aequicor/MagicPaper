package io.aequicor.magicpaper.data.research

import io.aequicor.magicpaper.data.checks.DefaultCommandChecks
import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.domain.checks.*
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class OwnedGitMetadataPolicyTest {
    @Test fun discoveryUsesFixedReadOnlyPolicyAndTheParentReceivesTrackedFileProtection() = runBlocking<Unit> {
        val root = Files.createTempDirectory("owned-policy-").toRealPath()
        val project = Files.createDirectory(root.resolve("project"))
        val git = Files.createDirectory(project.resolve(".git"))
        Files.writeString(project.resolve("package.json"), "{}")
        var metadata = 0
        var parents = 0
        val sandbox = object : ResearchSandbox {
            override fun prepare(command: List<String>, cwd: Path, environment: Map<String, String>, policy: ResearchWorkspacePolicy?,
                receiptId: String, receiptDirectory: Path, authorityRecorder: CheckAuthorityRecorder): PreparedCheckProcess {
                // The ordinary protected-policy OS probe is a separate owner command.
                val value = assertNotNull(policy)
                if (cwd == project) {
                    parents++
                    assertEquals(3, metadata)
                    assertTrue(git in value.protected)
                    assertFalse(project.resolve("dist") in value.writable)
                    assertContains(value.withheld.joinToString(), "файлы проекта")
                }
                return completed(receiptId, if (cwd == project) "" else "PROBE_DONE") {
                    if (cwd != project) {
                        Files.createDirectories(cwd.resolve("build"))
                        Files.writeString(cwd.resolve("build/ok.txt"), "allowed")
                    }
                }
            }
            override fun prepareBinary(command: List<String>, cwd: Path, environment: Map<String, String>, policy: ResearchWorkspacePolicy?,
                receiptId: String, receiptDirectory: Path, standardOutput: Path,
                authorityRecorder: CheckAuthorityRecorder): PreparedCheckProcess {
                metadata++
                val value = assertNotNull(policy)
                assertEquals(project, cwd)
                assertEquals(listOf(project), value.protected)
                assertEquals(listOf(standardOutput.parent), value.writable)
                assertFalse(value.writable.any { it.startsWith(project) })
                assertEquals("0", environment["GIT_OPTIONAL_LOCKS"])
                assertEquals("1", environment["GIT_CONFIG_NOSYSTEM"])
                assertFalse("GIT_INDEX_FILE" in environment)
                val output = if (command.contains("ls-files")) "dist/user.txt\u0000" else git.toString() + "\n"
                return completed(receiptId, "") { Files.write(standardOutput, output.encodeToByteArray()) }
            }
        }
        val driver = SandboxCheckDriver(root.resolve("checks"), 5_000) { sandbox }
        try {
            val owner = DefaultCommandChecks(InMemoryEventJournal(), InMemoryKeyValueStore(), driver)
            val program = Path.of(System.getProperty("java.home"), "bin",
                if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString()
            val result = owner.run(CheckCommand(CheckRef(CheckScope("project", "session", "request", 0), "build"),
                project.toString(), listOf(program)))
            assertEquals(0, result.exitCode)
            assertEquals(3, metadata)
            assertEquals(1, parents)
        } finally { driver.cleanup(); root.toFile().deleteRecursively() }
    }

    private fun completed(id: String, output: String, onRelease: () -> Unit): PreparedCheckProcess = object : PreparedCheckProcess() {
        override val receipt = CheckProcessReceipt(id, "controlled-group", 123)
        override fun release() = onRelease()
        override suspend fun stopAndConfirm() = NativeCheckCleanup("group", "authority")
        override fun getInputStream(): InputStream = output.byteInputStream()
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun waitFor() = 0
        override fun waitFor(timeout: Long, unit: TimeUnit) = true
        override fun exitValue() = 0
        override fun isAlive() = false
        override fun destroy() = Unit
    }
}
