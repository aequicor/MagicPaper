package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.tools.ToolExecutionContext
import io.aequicor.magicpaper.domain.tools.ToolRole
import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class GitTaskWorkspaceTest {
    private class Fixture {
        val root = Files.createTempDirectory("magicpaper-task-test-").toFile()
        val source = File(root, "source").apply { mkdirs() }
        val pool = File(root, "pool")
        val project = CodingProject("p", "Project", source.path, 1)
        val port = GitTaskWorkspace(pool)
        init {
            git(source, "init", "-b", "main")
            // Delivery checks out files with the real Git; the developer's global core.autocrlf must not decide their bytes.
            git(source, "config", "core.autocrlf", "false")
            source.resolve("base.txt").writeText("base\n")
            git(source, "add", "."); git(source, "commit", "-m", "base")
        }
        suspend fun open(id: String = "one"): TaskWorktree = port.describe(project, "session", id).also { port.open(it) }
        suspend fun prepare(record: TaskWorktree): TaskWorktree {
            val captured = record.copy(resultCommit = port.capture(record), targetCommit = port.target(record))
            return captured.copy(mergeCommit = checkNotNull(port.merge(captured)))
        }
    }
    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val f = Fixture()
        try { f.block() } finally { f.root.deleteRecursively() }
    }

    @Test fun unchangedTaskDoesNotInventCommit() = runTest { fixture {
        val before = git(source, "rev-parse", "HEAD")
        val record = prepare(open())
        port.deliver(record)
        assertEquals(before, git(source, "rev-parse", "HEAD"))
        assertTrue(port.delivered(record))
    } }

    @Test fun sourceIsUntouchedUntilDeliveryAndAgentCommitsSurvive() = runTest { fixture {
        val task = open()
        val dir = File(task.path)
        dir.resolve("agent.txt").writeText("committed")
        git(dir, "add", "."); git(dir, "commit", "-m", "agent commit")
        val agent = git(dir, "rev-parse", "HEAD")
        dir.resolve("remaining.txt").writeText("pending")
        val result = prepare(task)
        assertFalse(source.resolve("agent.txt").exists())
        assertEquals(task.baseCommit, git(source, "rev-parse", "HEAD"))
        port.verify(result)
        port.deliver(result)
        assertEquals("pending", source.resolve("remaining.txt").readText())
        assertEquals(agent, git(source, "rev-parse", "HEAD^"))
        assertEquals("", git(source, "status", "--porcelain"))
    } }

    @Test fun dirtyIndexAndUntrackedFilesBlockPreparation() = runTest { fixture {
        source.resolve("untracked").writeText("user")
        assertFailsWith<IllegalArgumentException> { port.describe(project, "s", "r") }
        git(source, "add", "untracked")
        assertFailsWith<IllegalArgumentException> { port.describe(project, "s", "r") }
        assertEquals("user", source.resolve("untracked").readText())
    } }

    @Test fun nonGitDetachedAndUnbornProjectsAreUnavailable() = runTest { fixture {
        val empty = File(root, "empty").apply { mkdirs() }
        assertFalse(port.availability(project.copy(path = empty.path)).available)
        git(empty, "init", "-b", "main")
        assertFalse(port.availability(project.copy(path = empty.path)).available)
        git(source, "checkout", "--detach")
        assertFalse(port.availability(project).available)
    } }

    @Test fun concurrentDestinationChangesAreMergedWithoutLosingEitherSide() = runTest { fixture {
        val task = open()
        File(task.path).resolve("task.txt").writeText("task")
        source.resolve("user.txt").writeText("user")
        git(source, "add", "."); git(source, "commit", "-m", "parallel user")
        val target = git(source, "rev-parse", "HEAD")
        val result = prepare(task)
        port.deliver(result)
        assertEquals("user", source.resolve("user.txt").readText())
        assertEquals("task", source.resolve("task.txt").readText())
        assertTrue(git(source, "rev-list", "--parents", "-n", "1", "HEAD").contains(target))
    } }

    @Test fun targetAdvanceWithoutTaskChangesDoesNotCreateMergeCommit() = runTest { fixture {
        val task = open()
        source.resolve("base.txt").appendText("next\n")
        git(source, "commit", "-am", "next")
        val target = git(source, "rev-parse", "HEAD")
        port.deliver(prepare(task))
        assertEquals(target, git(source, "rev-parse", "HEAD"))
    } }

    @Test fun conflictStaysInTaskCopyUntilRepair() = runTest { fixture {
        var task = open()
        File(task.path).resolve("base.txt").writeText("agent\n")
        task = task.copy(resultCommit = port.capture(task))
        source.resolve("base.txt").writeText("user\n")
        git(source, "commit", "-am", "user")
        task = task.copy(targetCommit = port.target(task))
        assertNull(port.merge(task))
        assertEquals("user\n", source.resolve("base.txt").readText())
        File(task.path).resolve("base.txt").writeText("user\nagent\n")
        git(File(task.path), "add", "base.txt")
        task = task.copy(mergeCommit = checkNotNull(port.merge(task)))
        port.deliver(task)
        assertEquals("user\nagent\n", source.resolve("base.txt").readText())
    } }

    @Test fun dirtyOrSwitchedDestinationCannotBeOverwritten() = runTest { fixture {
        val task = open()
        File(task.path).resolve("task").writeText("result")
        val result = prepare(task)
        source.resolve("user").writeText("keep")
        assertFailsWith<IllegalArgumentException> { port.deliver(result) }
        source.resolve("user").delete()
        git(source, "switch", "-c", "another")
        assertFailsWith<IllegalArgumentException> { port.deliver(result) }
        assertFalse(source.resolve("task").exists())
    } }

    @Test fun nextTaskReusesDirectoryButNotBranchAndRejectsDirtyPool() = runTest { fixture {
        val first = prepare(open())
        port.deliver(first)
        val next = port.describe(project, "session", "two").copy(reuseBranch = first.branch, reuseCommit = first.mergeCommit)
        assertEquals(first.path, next.path)
        File(first.path).resolve("unexpected").writeText("preserve")
        assertFailsWith<IllegalArgumentException> { port.open(next, first) }
        File(first.path).resolve("unexpected").delete()
        port.open(next, first)
        assertNotEquals(first.branch, next.branch)
        assertEquals(next.branch, git(File(next.path), "branch", "--show-current"))
        port.open(next) // replay of the saved preparation intent
    } }

    @Test fun crashAfterDeliveryIsRecognizedWithoutReapplying() = runTest { fixture {
        val task = open()
        File(task.path).resolve("result").writeText("once")
        val result = prepare(task)
        val interrupted = GitTaskWorkspace(pool, checkpoint = { if (it == "delivered") error("crash") })
        assertFailsWith<IllegalStateException> { interrupted.deliver(result) }
        val reopened = GitTaskWorkspace(pool)
        assertTrue(reopened.delivered(result))
        val head = git(source, "rev-parse", "HEAD")
        reopened.deliver(result)
        assertEquals(head, git(source, "rev-parse", "HEAD"))
    } }

    @Test fun destinationAdvanceHasExplicitOutcomeAndDoesNotOverwriteNewCommit() = runTest { fixture {
        val task = open()
        File(task.path).resolve("result").writeText("task")
        val result = prepare(task)
        source.resolve("user").writeText("later")
        git(source, "add", "."); git(source, "commit", "-m", "later")
        val head = git(source, "rev-parse", "HEAD")
        assertFailsWith<TaskDestinationChanged> { port.deliver(result) }
        assertEquals(head, git(source, "rev-parse", "HEAD"))
        assertFalse(source.resolve("result").exists())
    } }

    @Test fun failedLeaseReleaseCanBeReconciledBeforeRetryingDelivery() = runTest { fixture {
        val kv = InMemoryKeyValueStore()
        val repo = JsonCodingProjectRepository(kv, Json { encodeDefaults = true })
        repo.save(project)
        repo.saveSession(CodingSession("session", project.id, "Task", 1,
            pendingRun = CodingRunCheckpoint("request", "do", worktreeEnabled = true)))
        var armed = false
        val leases = GitPlanningWorkspace(File(root, "leases")) { event ->
            if (armed && event == "project-lock-releasing") { armed = false; error("injected release failure") }
        }
        val service = TaskWorktreeService(repo, port, leases)
        val task = service.begin(project, "session", "request")
        File(task.path).resolve("result").writeText("once")
        service.handoff(ToolExecutionContext(project.id, "session", "session", "request", ToolRole.CHAT, CodingInteractionMode.CODE), true, emptyList())
        armed = true
        assertFailsWith<IllegalStateException> { service.complete(project, "session", "request", repair = {}) }
        assertEquals(TaskWorktreePhase.COMPLETE, service.complete(project, "session", "request", repair = {}).phase)
        assertEquals("once", source.resolve("result").readText())
    } }

    @Test fun durableTaskReopensAcrossGenerationAndRequiresHandoff() = runTest { fixture {
        val kv = InMemoryKeyValueStore()
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        var repo = JsonCodingProjectRepository(kv, json)
        repo.save(project)
        repo.saveSession(CodingSession("session", project.id, "Task", 1, runtimeGeneration = 1,
            pendingRun = CodingRunCheckpoint("request", "do", worktreeEnabled = true)))
        var service = TaskWorktreeService(repo, port, GitPlanningWorkspace(File(root, "leases")))
        val first = service.begin(project, "session", "request")
        File(first.path).resolve("result").writeText("kept")
        assertFailsWith<IllegalStateException> { service.complete(project, "session", "request", repair = { error("unexpected") }) }
        repo = JsonCodingProjectRepository(kv, json)
        repo.updateSession(project.id, "session") { it.copy(runtimeGeneration = 2) }
        service = TaskWorktreeService(repo, GitTaskWorkspace(pool), GitPlanningWorkspace(File(root, "leases")))
        val resumed = service.begin(project, "session", "request")
        assertEquals(first.path, resumed.path)
        assertEquals(first.branch, resumed.branch)
        val ctx = ToolExecutionContext(project.id, "session", "session", "request", ToolRole.CHAT, CodingInteractionMode.CODE, runtimeGeneration = 2)
        assertFailsWith<IllegalArgumentException> { service.handoff(ctx.copy(runtimeGeneration = 1), true, emptyList()) }
        service.handoff(ctx, true, emptyList())
        val finished = service.complete(project, "session", "request", repair = { error("unexpected") })
        assertEquals(TaskWorktreePhase.COMPLETE, finished.phase)
        assertEquals("kept", source.resolve("result").readText())
    } }

    companion object {
        private fun git(dir: File, vararg args: String): String {
            val process = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@localhost", "-c", "commit.gpgSign=false") + args)
                .directory(dir).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { output }
            return output.trim()
        }
    }
}
