package io.aequicor.magicpaper.data.research

import io.aequicor.magicpaper.data.checks.DefaultCommandChecks
import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.domain.checks.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.test.*

/** Opt-in OS evidence: the real prepared process, containment and redirected handles remain in use. */
class CheckBinaryNativeTest {
    @Test fun nativeBinaryPipePreservesNulInvalidUtf8AndSeparatesStderr() = runBlocking<Unit> {
        assumeTrue(System.getProperty("magicpaper.research.native") == "true")
        val root = Files.createTempDirectory("native-binary-check-").toRealPath()
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val driver = SandboxCheckDriver(root.resolve("checks"), 10_000)
        try {
            val windows = System.getProperty("os.name").startsWith("Windows")
            val arguments = if (windows) listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "[Console]::OpenStandardOutput().Write([byte[]](0,255,10,13,192,128,0),0,7); [Console]::Error.Write('stderr')")
            else listOf("/bin/sh", "-c", "printf '\\000\\377\\012\\015\\300\\200\\000'; printf stderr >&2")
            val command = CheckCommand(CheckRef(CheckScope("project", "session", "request", 0), "binary"),
                workspace.toString(), arguments, policy = CheckPolicy.MANAGED_WORKTREE, outputMode = CheckOutputMode.BINARY_STDOUT)
            val owner = DefaultCommandChecks(InMemoryEventJournal(), InMemoryKeyValueStore(), driver)
            val result = owner.run(command)
            assertNull(result.blockedReason, result.toString())
            assertEquals(0, result.exitCode)
            assertEquals("stderr", result.output)
            assertContentEquals(byteArrayOf(0, -1, 10, 13, -64, -128, 0), owner.readOutput(command.ref))
        } finally { driver.cleanup(); root.toFile().deleteRecursively() }
    }
}
