package io.aequicor.magicpaper.data.research

import io.aequicor.magicpaper.data.checks.CheckProcessDriver
import io.aequicor.magicpaper.data.checks.CheckProbe
import io.aequicor.magicpaper.data.checks.DefaultCommandChecks
import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.domain.checks.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Настоящая ОС: запускает git и штатный драйвер проверок.
 *
 * Read-only Git — это точный allowlist аргументов (`isCheckGitReadArguments`), усиленный
 * `--no-optional-locks`, `GIT_OPTIONAL_LOCKS=0`, пустым `core.hooksPath` и отключёнными системным
 * и глобальным конфигурациями. Он не опирается на ограничение записи, которое доказывает probe,
 * поэтому отказ песочницы ОС не должен отключать чтения репозитория вместе с произвольными командами:
 * именно эта связь оставляла экран проекта, ожидание worktree и возобновление агента без Git.
 */
class ReadOnlyGitWithoutSandboxProbeNativeTest {
    private val enabled get() = System.getProperty("magicpaper.research.native") == "true"

    @Test fun readOnlyGitRunsWhileTheSandboxProbeIsRefusedAndProtectedCommandsStayRefused() = runBlocking {
        assumeTrue(enabled)
        val root = Files.createTempDirectory("readonly-git-")
        val project = Files.createDirectory(root.resolve("project"))
        try {
            Files.writeString(project.resolve("source.kt"), "CURRENT")
            val init = ProcessBuilder("git", "init", "-q").directory(project.toFile()).redirectErrorStream(true).start()
            assertEquals(0, init.waitFor(), init.inputStream.readAllBytes().decodeToString())

            val driver = RefusingProbe(SandboxCheckDriver(root.resolve("runtime"), 120_000))
            val owner = DefaultCommandChecks(InMemoryEventJournal(), InMemoryKeyValueStore(), driver)
            try {
                val scope = CheckScope("readonly-git", "s", UUID.randomUUID().toString(), 0)
                val read = CheckCommand(CheckRef(scope, "read"), project.toString(),
                    CheckGitReadQuery.ROOT.arguments(), policy = CheckPolicy.GIT_READ_ONLY,
                    outputMode = CheckOutputMode.BINARY_STDOUT)
                val result = owner.run(read)
                assertEquals(0, result.exitCode, result.toString())
                assertEquals(0, driver.probeRequests, "A read-only Git query must not need the sandbox probe")
                val reported = owner.readOutput(read.ref).decodeToString().trim().replace('\\', '/')
                assertEquals(project.toRealPath().toString().replace('\\', '/'), reported)
                assertEquals("CURRENT", Files.readString(project.resolve("source.kt")))

                val protectedCommand = CheckCommand(CheckRef(scope.copy(requestId = UUID.randomUUID().toString()), "run"),
                    project.toString(), listOf("git", "status"), policy = CheckPolicy.PROTECTED_PROJECT)
                assertFailsWith<IOException> { owner.run(protectedCommand) }
                assertEquals(1, driver.probeRequests, "An arbitrary protected command still requires the probe")
            } finally { owner.close() }
        } finally { root.toFile().deleteRecursively() }
    }

    /** A fixture refusal is what a platform with a broken containment mechanism reports; it reaches the
     *  requesting caller unchanged, which is the contract CheckPreparationOwnerTest already pins. */
    private class RefusingProbe(delegate: CheckProcessDriver) : CheckProcessDriver by delegate {
        var probeRequests = 0
        override suspend fun createProbe(ref: CheckRef): CheckProbe {
            probeRequests++
            throw IOException("controlled probe refusal")
        }
    }

    @Test fun platformsThatConfineWritesStillBindReadOnlyGitIntoTheSandbox() {
        // The relaxation belongs to a platform that cannot confine writes at all; it must not be
        // readable as a general exemption from the filesystem policy. Windows confines writes with a
        // low-integrity token — the native sandbox suite asserts the refusals on a real OS — so it keeps
        // the policy, and flipping this back to false means the sandbox stopped confining writes there.
        assertTrue(LinuxResearchSandbox.confinesWrites)
        assertTrue(MacResearchSandbox.confinesWrites)
        assertTrue(WindowsResearchSandbox.confinesWrites,
            "Without write confinement on Windows read-only Git would lose its filesystem policy too")
    }
}
