package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.DefaultSessionOrganismStore
import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SessionCodingLeaseCleanupTest {
    @Test fun captureFailureSurvivesReleaseErrorAndTheExactLeaseRemainsUnknown() = runTest {
        val fixture = Fixture()
        val lease = fixture.open()
        val captureFailure = IOException("capture failed")
        val releaseFailure = CleanupError()
        fixture.port.captureResult = { throw captureFailure }
        fixture.port.beforeRelease = { throw releaseFailure }

        val thrown = assertFailsWith<IOException> { fixture.workspaces.finish(lease, success = true, report = "result") }

        assertOriginalAndCleanup(thrown, captureFailure, releaseFailure)
        fixture.assertRetainedUnknown(lease)
        fixture.port.beforeRelease = {}
        fixture.workspaces.releaseStopped(fixture.session.id)
        assertTrue(fixture.workspaces.retainedSessionIds().isEmpty())
        assertNull(fixture.port.holderOf(lease.owner.path))
    }

    @Test fun actualCaptureCancellationSurvivesReleaseErrorAndKeepsTheLeaseForCleanup() = runTest {
        val fixture = Fixture()
        val lease = fixture.open()
        val original = CancellationException("capture cancelled")
        val releaseFailure = CleanupError()
        val observed = CompletableDeferred<Throwable>()
        fixture.port.captureResult = {
            currentCoroutineContext().cancel(original)
            throw original
        }
        fixture.port.beforeRelease = { throw releaseFailure }
        val worker = launch {
            try {
                fixture.workspaces.finish(lease, success = true, report = "result")
                error("Cancelled capture must not complete")
            } catch (cancelled: CancellationException) {
                observed.complete(cancelled)
                throw cancelled
            }
        }
        worker.join()

        assertTrue(worker.isCancelled)
        assertTrue(observed.isCompleted)
        assertOriginalAndCleanup(observed.await(), original, releaseFailure)
        fixture.assertRetainedUnknown(lease)
        fixture.port.beforeRelease = {}
        fixture.workspaces.releaseStopped(fixture.session.id)
        assertTrue(fixture.workspaces.retainedSessionIds().isEmpty())
    }

    @Test fun stoppedCleanupAttemptsEveryRetainedHandleAndKeepsUnknownWhileOneReleaseStillFails() = runTest {
        val fixture = Fixture()
        fixture.initialize()
        val releaseFailure = CleanupError()
        fixture.port.beforeRelease = { throw releaseFailure }
        assertFailsWith<CleanupError> { fixture.workspaces.open(fixture.project, fixture.session, fixture.task) }
        val source = fixture.port.acquired.single { it.canonicalPath == fixture.source.canonicalPath }
        val execution = fixture.port.acquired.single { it.canonicalPath == fixture.execution.canonicalPath }
        assertEquals(setOf(fixture.session.id), fixture.workspaces.retainedSessionIds())
        fixture.port.releaseAttempts.clear()
        fixture.port.beforeRelease = { handle -> if (handle == source) throw releaseFailure }

        assertFailsWith<CleanupError> { fixture.workspaces.releaseStopped(fixture.session.id) }

        assertEquals(listOf(source.token, execution.token), fixture.port.releaseAttempts.map { it.token })
        assertEquals(source.ownerId, fixture.port.holderOf(fixture.source.path))
        assertNull(fixture.port.holderOf(fixture.execution.path), "Failure on the first lease must not skip the other cleanup")
        assertEquals(setOf(fixture.session.id), fixture.workspaces.retainedSessionIds())
        assertEquals(SessionCodingWorkspacePhase.UNKNOWN, fixture.persistedWorkspace().phase)
        fixture.port.beforeRelease = {}
        fixture.port.releaseAttempts.clear()
        fixture.workspaces.releaseStopped(fixture.session.id)
        assertEquals(listOf(source.token), fixture.port.releaseAttempts.map { it.token })
        assertTrue(fixture.workspaces.retainedSessionIds().isEmpty())
        assertNull(fixture.port.holderOf(fixture.source.path))
    }

    @Test fun latePreviousGenerationCleanupCannotReleaseTheNewWorkspaceLease() = runTest {
        val fixture = Fixture()
        val old = fixture.open()
        fixture.workspaces.finish(old, success = false, report = "")
        fixture.store.observe(old.organismId, old.sessionId, old.record.generation, SessionObservedState.STOPPED)
        fixture.beginNextRun()
        val current = fixture.workspaces.open(fixture.project, fixture.session, fixture.task)
        val oldHandle = assertNotNull(old.workspaceLease)
        val currentHandle = assertNotNull(current.workspaceLease)
        assertEquals(oldHandle.canonicalPath, currentHandle.canonicalPath)
        assertNotEquals(oldHandle.token, currentHandle.token)
        assertTrue(current.record.generation > old.record.generation)
        val before = fixture.persistedWorkspace()

        assertFailsWith<IllegalArgumentException> { fixture.workspaces.finish(old, success = false, report = "late old completion") }

        assertEquals(currentHandle.ownerId, fixture.port.holderOf(current.owner.path))
        assertNull(fixture.port.acquire(current.owner.copy(id = "another-owner"), "another-request"))
        assertEquals(before, fixture.persistedWorkspace(), "A stale completion cannot rewrite the current generation")
        fixture.workspaces.finish(current, success = false, report = "")
        assertNull(fixture.port.holderOf(current.owner.path))
    }

    private class CleanupError : Error("release failed")

    private fun assertOriginalAndCleanup(thrown: Throwable, original: Throwable, cleanup: Throwable) {
        val causes = generateSequence(thrown) { it.cause }.toList()
        assertTrue(causes.any { it === original }, "The primary failure must survive cleanup")
        assertTrue(causes.flatMap { it.suppressedExceptions }.any { suppressed ->
            generateSequence(suppressed) { it.cause }.any { it === cleanup }
        }, "The cleanup failure must remain attached to the original failure")
    }

    /** Only filesystem outcomes are controlled. Admission, generations and workspace checkpoints
     * pass through the real organism reducer and input journal. No native recovery proof is forged. */
    private class Fixture {
        val source = Files.createTempDirectory("session-lease-source-").toFile()
        val execution = Files.createTempDirectory("session-lease-execution-").toFile()
        val project = CodingProject("project", "Project", source.path, 1)
        private val storage = InMemoryKeyValueStore()
        private val journal = InMemoryEventJournal()
        val store = DefaultSessionOrganismStore(storage, journal, dispatcher = Dispatchers.Unconfined, clock = { 1_000 })
        val port = ControlledWorkspace(execution)
        val workspaces = SessionCodingWorkspaces(port, store)
        var session = CodingSession("session", project.id, "Session", 1)
            private set
        val task = SessionTask("Work", session.id, "Verified result", sourceVersion = "source-snapshot")

        suspend fun initialize() {
            val organism = store.adopt(project.id, session, emptyList())
            session = session.copy(organismId = organism.id)
            beginNextRun()
        }

        suspend fun beginNextRun() {
            val node = store.beginRun(checkNotNull(session.organismId), session.id)
            session = session.copy(runtimeGeneration = node.generation)
        }

        suspend fun open(): SessionCodingWorkspaces.Lease {
            initialize()
            return workspaces.open(project, session, task)
        }

        suspend fun persistedWorkspace(): SessionCodingWorkspace = checkNotNull(
            DefaultSessionOrganismStore(storage, journal, dispatcher = Dispatchers.Unconfined, clock = { 1_000 })
                .get(checkNotNull(session.organismId)).sessions.getValue(session.id).workspace,
        )

        suspend fun assertRetainedUnknown(lease: SessionCodingWorkspaces.Lease) {
            assertEquals(SessionCodingWorkspacePhase.UNKNOWN, lease.record.phase)
            assertEquals(SessionCodingWorkspacePhase.UNKNOWN, persistedWorkspace().phase)
            assertEquals(setOf(session.id), workspaces.retainedSessionIds())
            assertEquals(lease.owner.id, port.holderOf(lease.owner.path))
            assertNull(port.acquire(lease.owner.copy(id = "other-owner"), "other-request"))
        }
    }

    private class ControlledWorkspace(private val execution: File, private val leases: LocalPlanningWorkspace = LocalPlanningWorkspace()) : PlanningWorkspace by leases {
        val acquired = mutableListOf<WorkspaceLease>()
        val releaseAttempts = mutableListOf<WorkspaceLease>()
        var beforeRelease: (WorkspaceLease) -> Unit = {}
        var captureResult: suspend (StageAttempt) -> String = { "result-commit" }

        override suspend fun acquire(project: CodingProject, requestId: String): WorkspaceLease? =
            leases.acquire(project, requestId)?.also { acquired += it }
        override suspend fun release(lease: WorkspaceLease) {
            releaseAttempts += lease
            beforeRelease(lease)
            leases.release(lease)
        }
        override suspend fun prepare(project: CodingProject, runId: String, operation: WorkspaceOperation) = PlanWorkspace(execution.path, execution.path, git = true)
        override suspend fun stage(project: CodingProject, workspace: PlanWorkspace, attempt: StageAttempt, operation: WorkspaceOperation) =
            attempt.copy(path = execution.path, baseCommit = "source-commit")
        override suspend fun verificationSnapshot(path: String, operation: WorkspaceOperation?) = "source-snapshot"
        override suspend fun capture(attempt: StageAttempt, operation: WorkspaceOperation) = captureResult(attempt)
    }
}
