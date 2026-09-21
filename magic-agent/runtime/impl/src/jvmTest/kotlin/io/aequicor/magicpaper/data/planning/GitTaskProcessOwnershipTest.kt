package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.data.workspace.DefaultTaskWorktreeOwner
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.checks.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Controlled Git protocol tests; native process-group containment remains in checks' OS acceptance. */
class GitTaskProcessOwnershipTest {
    private class Fixture {
        val directory = Files.createTempDirectory("task-git-owner-").toFile()
        val source = File(directory, "source").apply { mkdirs() }
        val root = File(directory, "pool")
        val real = testGitChecks()
        val seen = mutableListOf<CheckCommand>()
        var unknown: CheckCommand? = null
        var refuse: (CheckCommand) -> Boolean = { false }
        val checks = object : CommandChecks by real {
            override suspend fun run(command: CheckCommand): CheckResult {
                seen += command
                if (refuse(command)) { unknown = command; throw CheckOutcomeUnknown() }
                return real.run(command)
            }
            override suspend fun unresolved(resource: String) = unknown?.takeIf { resource in it.resources }?.let { setOf(it.ref) }.orEmpty()
        }
        val authority = GitWorkspaceAuthority(checks, File(directory, "locks"))
        val planning = GitPlanningWorkspace(File(directory, "planning"), authority)
        val task = GitTaskWorkspace(authority, root)
        val ownerId = TaskWorktreeOwnerId("project", "session")
        val events = InMemoryEventJournal()
        val payloads = InMemoryKeyValueStore()
        val owner = DefaultTaskWorktreeOwner(task, events, payloads)
        val project = CodingProject(ownerId.projectId, "Project", source.path, 0)
        init {
            git("init", "-b", "main")
            source.resolve("base").writeText("base")
            git("add", "."); git("commit", "-m", "base")
        }
        private fun git(vararg args: String) {
            val process = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid") + args)
                .directory(source).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { output }
        }
        suspend fun prepare(): Pair<TaskWorktree, TaskWorkspaceLeases> {
            val record = task.describe(project, ownerId.sessionId, "task", "Task")
            val sourceLease = checkNotNull(planning.acquire(project.copy(id = "source"), "source-request"))
            val taskLease = checkNotNull(planning.acquire(project.copy(id = "execution", path = record.path), "task-request"))
            owner.projection(ownerId, generation = 7)
            return record to TaskWorkspaceLeases(sourceLease, taskLease)
        }
    }

    @Test fun durablePendingOwnsEveryCommandAndUnknownOpenRetainsBothHandles() = runTest {
        val f = Fixture()
        val (record, leases) = f.prepare()
        f.seen.clear()
        f.refuse = { it.arguments.contains("worktree") }
        assertFailsWith<IllegalStateException> { f.owner.accept(f.ownerId,
            TaskWorktreeMachine.Input.Intent.Prepare(record, 7, "durable-open"), leases) }
        val dispatched = checkNotNull(f.unknown)
        assertEquals(CheckScope("project", "session", "durable-open", 7), dispatched.ref.scope)
        assertTrue(dispatched.ref.callId.startsWith("task-open:"))
        assertTrue(record.path in dispatched.resources)
        assertTrue(f.source.canonicalPath in dispatched.resources)
        assertTrue(f.owner.projection(f.ownerId).unknown)
        for (lease in listOfNotNull(leases.source, leases.execution)) {
            assertFailsWith<CheckOutcomeUnknown> { f.planning.release(lease) }
            assertEquals(lease.ownerId, f.planning.holderOf(lease.canonicalPath))
            assertFailsWith<CheckOutcomeUnknown> { f.planning.acquire(f.project.copy(id = "fresh", path = lease.canonicalPath), "fresh") }
        }
        val count = f.seen.size
        val reopened = DefaultTaskWorktreeOwner(f.task, f.events, f.payloads)
        assertTrue(reopened.projection(f.ownerId).unknown)
        assertEquals(count, f.seen.size, "Restore must not run Git")
    }

    @Test fun wrongSourceLeaseAndForeignSlotAreRejectedBeforeAnyCommand() = runTest {
        for (foreignSlot in listOf(false, true)) {
            val f = Fixture()
            val (record, leases) = f.prepare()
            val pending = TaskWorktreeMachine.Pending("open", TaskWorktreeMachine.Operation.OPEN, record.taskId, 7, null)
            val bad = if (foreignSlot) record.copy(path = File(f.root, "other-session").path) else record
            val wrong = if (foreignSlot) leases else leases.copy(source = leases.execution)
            f.seen.clear()
            assertFailsWith<IllegalArgumentException> { f.task.open(bad, TaskWorkspaceOperation(f.ownerId, pending, wrong)) }
            assertTrue(f.seen.isEmpty())
            f.planning.release(checkNotNull(leases.execution)); f.planning.release(checkNotNull(leases.source))
        }
    }

    @Test fun staleReleaseAndChangedMetadataCannotUnlockNewGeneration() = runTest {
        val f = Fixture()
        val old = checkNotNull(f.planning.acquire(f.project, "old"))
        f.planning.release(old)
        val current = checkNotNull(f.planning.acquire(f.project, "new"))
        f.planning.release(old)
        assertFailsWith<IllegalArgumentException> { f.planning.release(current.copy(requestId = "forged")) }
        assertEquals(current.ownerId, f.planning.holderOf(current.canonicalPath))
        assertFailsWith<IllegalArgumentException> { f.authority.owned(listOf(old),
            CheckScope("project", "session", "stale", 0), "stale") { error("No command") } }
        f.planning.release(current)
    }
}
