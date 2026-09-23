package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.checks.createCommandChecks
import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.domain.CodingProject
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Worktree mode is available exactly when the OS sandbox can run the read-only Git commands that decide
 * it. On Windows that chain broke twice without any test noticing: the restoration proof was
 * unsatisfiable, and a write-restricted child could not start at all, so every Git read was refused and
 * [GitTaskWorkspace.availability] permanently reported «Проверка Git временно недоступна». The chain is
 * the OS sandbox probe, a `git` launcher that spawns its real binary, and binary stdout of the child.
 * Opt-in like every other native sandbox check: `-Pmagicpaper.research.native=true`.
 */
class GitTaskWorkspaceAvailabilityNativeTest {
    @Test fun realGitProjectConfirmsWorktreeAvailability() = runBlocking<Unit> {
        assumeTrue(System.getProperty("magicpaper.research.native") == "true")
        val root = Files.createTempDirectory("worktree-availability-")
        val project = Files.createDirectories(root.resolve("project")).toFile()
        try {
            git(project, "init", "-q", "-b", "main")
            git(project, "commit", "--allow-empty", "-q", "-m", "base")
            val checks = createCommandChecks(InMemoryEventJournal(), InMemoryKeyValueStore(), root.resolve("checks"),
                TaskWorktreeIntegrationChecks.DEFAULT_TIMEOUT_MILLIS)
            try {
                val workspace = GitTaskWorkspace(GitWorkspaceAuthority(checks, root.resolve("planning").toFile()),
                    root.resolve("task-worktrees").toFile())
                val capability = workspace.availability(CodingProject("project", "project", project.canonicalPath, 0))
                assertTrue(capability.available,
                    capability.reason ?: "Режим worktree обязан быть доступным в настоящем Git-проекте")
            } finally { checks.close() }
        } finally { root.toFile().deleteRecursively() }
    }

    private fun git(dir: File, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=MagicPaper", "-c", "user.email=tasks@localhost") + arguments)
            .directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().decodeToString()
        check(process.waitFor() == 0) { "git ${arguments.first()} завершился ошибкой: $output" }
    }
}
