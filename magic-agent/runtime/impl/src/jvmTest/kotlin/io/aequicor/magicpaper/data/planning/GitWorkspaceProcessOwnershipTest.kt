package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.checks.*
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class GitWorkspaceProcessOwnershipTest {
    @Test fun leaseWaitsForActiveNeighbour() = runTest {
        val project = project()
        val neighbour = CheckRef(CheckScope("other", "session", "request", 0), "check")
        val finished = CompletableDeferred<Unit>()
        var active = true
        val checks = object : CommandChecks by testGitChecks() {
            override suspend fun unresolved(resource: String): Set<CheckRef> =
                if (resource == project.path && active) setOf(neighbour) else emptySet()
            override suspend fun awaitActive(ref: CheckRef): Boolean {
                assertEquals(neighbour, ref)
                if (!active) return false
                finished.await()
                active = false
                return true
            }
        }
        val port = testGitPlanningWorkspace(Files.createTempDirectory("git-neighbour-data").toFile(), checks)
        val pending = async { port.acquire(project, "request") }
        yield()
        assertFalse(pending.isCompleted)
        finished.complete(Unit)
        val lease = assertNotNull(pending.await())
        port.release(lease)
    }

    @Test fun unknownCommandKeepsExactLeaseAndReopenCannotGrantAnotherWriter() = runTest {
        val project = project()
        val checks = ControlledChecks()
        val data = Files.createTempDirectory("git-owned-data").toFile()
        val port = testGitPlanningWorkspace(data, checks)
        val lease = assertNotNull(port.acquire(project, "request"))
        checks.unknown = true
        assertFailsWith<CheckOutcomeUnknown> { port.prepare(project, "run", WorkspaceOperation(lease, "journal:17")) }
        val command = assertNotNull(checks.command)
        assertEquals(lease.canonicalPath, command.protectedResource)
        assertEquals(lease.token, command.ref.scope.sessionId)
        assertEquals(lease.requestId, command.ref.scope.requestId)
        assertTrue(command.ref.callId.startsWith("journal:17:"))
        // A second operation must be fenced before even a filesystem-only branch can run.
        val before = checks.calls
        assertFailsWith<CheckOutcomeUnknown> { port.prepare(project, "another-run", WorkspaceOperation(lease, "journal:18")) }
        assertEquals(before, checks.calls)
        assertFailsWith<CheckOutcomeUnknown> { port.release(lease) }
        assertEquals(lease.ownerId, port.holderOf(project.path))
        assertFailsWith<CheckOutcomeUnknown> { testGitPlanningWorkspace(data, checks).acquire(project, "fresh") }
        val target = command.affectedResources.single { java.io.File(it).name == "integration" }
        java.io.File(target).mkdirs() // The external worktree command may create the target before its outcome is lost.
        assertFailsWith<CheckOutcomeUnknown> {
            testGitPlanningWorkspace(data, checks).acquire(project.copy(id = "target-writer", path = target), "fresh-target")
        }
        checks.unknown = false
        port.reconcile(StageAttempt("a", "s", StageAssignment("", ""), path = project.path), WorkspaceOperation(lease, "inspect"))
        port.release(lease)
        val next = assertNotNull(port.acquire(project, "next"))
        port.release(lease)
        assertEquals(next.ownerId, port.holderOf(project.path))
        port.release(next)
    }

    @Test fun activeCommandCannotLoseItsLeaseAndCancelledUnknownRetainsIt() = runTest {
        val project = project()
        val entered = CompletableDeferred<Unit>()
        val checks = ControlledChecks().apply { block = { entered.complete(Unit); awaitCancellation() } }
        val port = testGitPlanningWorkspace(Files.createTempDirectory("git-active-data").toFile(), checks)
        val lease = assertNotNull(port.acquire(project, "request"))
        val job = launch(Dispatchers.Default) { port.prepare(project, "run", WorkspaceOperation(lease, "journal:8")) }
        entered.await()
        assertFailsWith<IllegalStateException> { port.release(lease) }
        assertEquals(project.id, port.holderOf(project.path))
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFailsWith<CheckOutcomeUnknown> { port.release(lease) }
        assertEquals(project.id, port.holderOf(project.path))
        checks.unknown = false
        port.release(lease)
    }

    @Test fun forgedAndPreviouslyReleasedTokensCannotStartGit() = runTest {
        val project = project()
        val checks = ControlledChecks()
        val port = testGitPlanningWorkspace(Files.createTempDirectory("git-stale-data").toFile(), checks)
        val old = assertNotNull(port.acquire(project, "request"))
        port.release(old)
        val current = assertNotNull(port.acquire(project, "request"))
        for (invalid in listOf(old, current.copy(requestId = "foreign"), current.copy(ownerId = "foreign"))) {
            assertFailsWith<IllegalArgumentException> { port.prepare(project, "run", WorkspaceOperation(invalid, "operation")) }
        }
        val foreign = project.copy(path = Files.createTempDirectory("git-foreign-source").toString())
        assertFailsWith<IllegalArgumentException> { port.prepare(foreign, "run", WorkspaceOperation(current, "foreign-path")) }
        assertNull(checks.command)
        port.release(current)
    }

    @Test fun foreignWorkspaceTupleCannotBorrowAnIntegrationOrSourceLease() = runTest {
        val source = project()
        val checks = ControlledChecks()
        val data = Files.createTempDirectory("git-tuple-data").toFile()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(java.io.File(source.path).canonicalPath.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
        val ownRun = java.io.File(data, "$hash/own").apply { mkdirs() }
        val foreignRun = java.io.File(data, "$hash/foreign").apply { mkdirs() }
        val integration = java.io.File(ownRun, "integration").apply { mkdirs() }
        val port = testGitPlanningWorkspace(data, checks)
        val execution = assertNotNull(port.acquire(source.copy(path = integration.path), "execution"))
        val forged = PlanWorkspace(foreignRun.path, integration.path, git = true)
        assertFailsWith<IllegalArgumentException> {
            port.stage(source, forged, StageAttempt("attempt", "session", StageAssignment("", "")), WorkspaceOperation(execution, "stage"))
        }
        port.release(execution)
        val lease = assertNotNull(port.acquire(source, "source"))
        val otherProject = java.io.File(data, "foreign-project/run").apply { mkdirs() }
        val otherIntegration = java.io.File(otherProject, "integration").apply { mkdirs() }
        assertFailsWith<IllegalArgumentException> {
            port.apply(source, PlanWorkspace(otherProject.path, otherIntegration.path, git = true), WorkspaceOperation(lease, "apply"))
        }
        assertNull(checks.command)
        port.release(lease)
    }

    @Test fun positivePreDispatchRefusalReleasesTheLeaseWithoutTreatingMissingReceiptAsProof() = runTest {
        val project = project()
        val known = mutableMapOf<CheckRef, CheckResult>()
        val checks = object : CommandChecks by testGitChecks() {
            override suspend fun run(command: CheckCommand): CheckResult = CheckResult("", null, "Capability unavailable")
                .also { known[command.ref] = it }
            override suspend fun inspect(ref: CheckRef) = known[ref]
        }
        val port = testGitPlanningWorkspace(Files.createTempDirectory("git-refused-data").toFile(), checks)
        val lease = assertNotNull(port.acquire(project, "request"))
        assertFailsWith<GitCommandUnavailable> {
            port.validateExecutionPath(project, project.path, WorkspaceOperation(lease, "probe"))
        }
        assertTrue(known.isNotEmpty())
        port.release(lease)
        val next = assertNotNull(port.acquire(project, "retry"))
        port.release(next)
    }

    private fun project() = CodingProject("project", "Project", Files.createTempDirectory("git-owned-source").toString(), 0)
    private class ControlledChecks : CommandChecks by testGitChecks() {
        var command: CheckCommand? = null
        var calls = 0
        var unknown = false
        var block: (suspend () -> Unit)? = null
        override suspend fun run(command: CheckCommand): CheckResult {
            this.command = command
            calls++
            unknown = true
            block?.invoke()
            throw CheckOutcomeUnknown()
        }
        override suspend fun inspect(ref: CheckRef): CheckResult? = if (unknown) null else CheckResult("", 1)
        override suspend fun unresolved(resource: String): Set<CheckRef> = command?.takeIf { unknown && resource in it.resources }
            ?.let { setOf(it.ref) }.orEmpty()
    }
}
