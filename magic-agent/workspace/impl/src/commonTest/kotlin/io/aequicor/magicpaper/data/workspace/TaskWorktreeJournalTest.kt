package io.aequicor.magicpaper.data.workspace

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TaskWorktreeJournalTest {
    private val owner = TaskWorktreeOwnerId("project", "session")
    private fun record() = TaskWorktree("task", "/private/source", "main", "base", "/copies/session", "branch", label = "private task text")
    private class Workspace : TaskWorkspace {
        var writes = 0
        var reads = 0
        var before: suspend () -> Unit = {}
        var failure: Exception? = null
        var evidence: (TaskWorktreeMachine.Pending) -> TaskWorktreeInspection = { TaskWorktreeInspection.Unknown }
        private suspend fun write() { before(); writes++; failure?.let { throw it } }
        override suspend fun availability(project: CodingProject) = WorktreeAvailability(true)
        override suspend fun describe(project: CodingProject, sessionId: String, taskId: String, label: String) = error("not used")
        override suspend fun open(record: TaskWorktree, operation: TaskWorkspaceOperation, previous: TaskWorktree?) = write()
        override suspend fun reconcile(record: TaskWorktree) { reads++ }
        override suspend fun capture(record: TaskWorktree, operation: TaskWorkspaceOperation): String { write(); return "result" }
        override suspend fun target(record: TaskWorktree) = "target"
        override suspend fun refresh(record: TaskWorktree, operation: TaskWorkspaceOperation): TaskWorktreeRefresh { write(); return TaskWorktreeRefresh() }
        override suspend fun integrate(record: TaskWorktree, operation: TaskWorkspaceOperation): String { write(); return "merge" }
        override suspend fun verify(record: TaskWorktree, operation: TaskWorkspaceOperation) = write()
        override suspend fun deliver(record: TaskWorktree, operation: TaskWorkspaceOperation) = write()
        override suspend fun delivered(record: TaskWorktree) = false
        override suspend fun inspect(record: TaskWorktree, pending: TaskWorktreeMachine.Pending): TaskWorktreeInspection { reads++; return evidence(pending) }
    }
    private class Journal(val real: EventJournal = InMemoryEventJournal()) : EventJournal by real {
        var onAppend: suspend (JournalRevision, String, Long, String) -> JournalRecord? = { revision, operation, at, detail -> real.append(revision, operation, at, detail) }
        var onRead: suspend (String) -> JournalSnapshot = { real.snapshot(it) }
        var onStreams: suspend () -> List<String> = { real.streams() }
        override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String) = onAppend(expected, operation, at, detail)
        override suspend fun snapshot(stream: String) = onRead(stream)
        override suspend fun streams() = onStreams()
    }
    private class Fixture {
        val payloads = InMemoryKeyValueStore()
        val events = Journal()
        val workspace = Workspace()
        fun owner(dispatcher: CoroutineDispatcher = Dispatchers.IO) = DefaultTaskWorktreeOwner(workspace, events, payloads, dispatcher)
    }
    private suspend fun DefaultTaskWorktreeOwner.prepare() {
        projection(owner, generation = 1)
        acceptWithLeases(owner, Input.Intent.Prepare(record(), 1, "open"))
    }

    private suspend fun DefaultTaskWorktreeOwner.acceptWithLeases(id: TaskWorktreeOwnerId, intent: Input.Intent): TaskWorktreeProjection {
        val leases = LocalPlanningWorkspace()
        val source = checkNotNull(leases.acquire(CodingProject("source", "Source", record().sourcePath, 0), "source"))
        val execution = checkNotNull(leases.acquire(CodingProject("execution", "Task", record().path, 0), "execution"))
        try { return accept(id, intent, TaskWorkspaceLeases(source, execution)) }
        finally { withContext(NonCancellable) { leases.release(execution); leases.release(source) } }
    }

    @Test fun effectFollowsDurableIntentAndJournalContainsNoPathsCommandsOrText() = runTest {
        val f = Fixture(); val service = f.owner()
        service.projection(owner, generation = 1)
        // Inspect the committed bytes directly without creating another writer of the same stream.
        f.workspace.before = { assertTrue(f.events.real.read(service.projectionStream()).size >= 2) }
        service.acceptWithLeases(owner, Input.Intent.Prepare(record(), 1, "open"))
        assertEquals(1, f.workspace.writes)
        val detail = f.events.real.read(service.projectionStream()).joinToString { it.detail }
        assertFalse(detail.contains("private task text")); assertFalse(detail.contains("/private/source"))
        assertTrue(f.payloads.keys("worktree-input:").isNotEmpty())
    }
    private fun DefaultTaskWorktreeOwner.projectionStream() = "task-worktree:cHJvamVjdA:c2Vzc2lvbg"

    @Test fun exactLostAcknowledgementExecutesOnceAndRestoreExecutesNothing() = runTest {
        val f = Fixture(); val service = f.owner(); service.projection(owner, generation = 1)
        f.events.onAppend = { revision, operation, at, detail -> f.events.real.append(revision, operation, at, detail); throw IllegalStateException("lost ack") }
        val result = service.acceptWithLeases(owner, Input.Intent.Prepare(record(), 1, "open"))
        assertEquals(TaskWorktreePhase.RUNNING, result.task?.phase)
        assertEquals(1, f.workspace.writes)
        val restored = f.owner().projection(owner)
        assertFalse(restored.unknown)
        assertEquals(1, f.workspace.writes); assertEquals(0, f.workspace.reads)
    }

    @Test fun failedIntentAndForgedAcknowledgementNeverReachGitAndRemainFenced() = runTest {
        for (forged in listOf(false, true)) {
            val f = Fixture(); val service = f.owner(); service.projection(owner, generation = 1)
            f.events.onAppend = { revision, operation, at, detail ->
                if (forged) JournalRecord(revision.seq + 1, at, "foreign", operation, detail)
                else throw IllegalStateException("write failed")
            }
            assertFailsWith<TaskWorktreeJournalUnknown> { service.acceptWithLeases(owner, Input.Intent.Prepare(record(), 1, "open")) }
            f.events.onAppend = { revision, operation, at, detail -> f.events.real.append(revision, operation, at, detail) }
            assertFailsWith<TaskWorktreeJournalUnknown> { service.acceptWithLeases(owner, Input.Intent.Prepare(record(), 1, "again")) }
            assertEquals(0, f.workspace.writes)
        }
    }

    @Test fun unconfirmedGitCannotRepeatOnRestoreAndOnlyExplicitMatchingInspectionCanResolveIt() = runTest {
        val f = Fixture(); val service = f.owner(); service.projection(owner, generation = 1)
        f.workspace.failure = IllegalStateException("failure after effect")
        assertFailsWith<TaskWorktreeOperationUnknown> { service.acceptWithLeases(owner, Input.Intent.Prepare(record(), 1, "open")) }
        f.workspace.failure = null
        val reopened = f.owner()
        assertTrue(reopened.projection(owner).unknown)
        assertEquals(1, f.workspace.writes); assertEquals(0, f.workspace.reads)
        assertFailsWith<TaskWorktreeRejected> { reopened.acceptWithLeases(owner, Input.Intent.BindRun("task", 2)) }
        assertTrue(reopened.acceptWithLeases(owner, Input.Intent.Inspect("task")).unknown)
        f.workspace.evidence = { TaskWorktreeInspection.Confirmed(TaskWorktreeProof(it.id, it.taskId, it.kind)) }
        assertFalse(reopened.acceptWithLeases(owner, Input.Intent.Inspect("task")).unknown)
        assertEquals(1, f.workspace.writes); assertEquals(2, f.workspace.reads)
    }

    @Test fun missingPayloadCorruptionAndChangedJournalPrefixFailClosed() = runTest {
        for (kind in listOf("missing", "corrupt", "prefix")) {
            val f = Fixture(); val service = f.owner(); service.prepare()
            when (kind) {
                "missing" -> f.payloads.delete(f.payloads.keys("worktree-input:").first())
                "corrupt" -> f.payloads.write(f.payloads.keys("worktree-input:").first(), "{}")
                "prefix" -> f.events.onRead = { stream -> f.events.real.snapshot(stream).let { it.copy(records = it.records.mapIndexed { index, r -> if (index == 0) r.copy(stream = "foreign") else r }) } }
            }
            assertFailsWith<TaskWorktreeJournalUnknown> { f.owner().projection(owner) }
            assertEquals(1, f.workspace.writes)
        }
    }

    @Test fun lostAckWithRewrittenPrefixAndResetEpochAreRejected() = runTest {
        for (kind in listOf("prefix", "epoch")) {
            val f = Fixture(); val service = f.owner(); service.projection(owner, generation = 1)
            f.events.onAppend = { revision, operation, at, detail ->
                f.events.real.append(revision, operation, at, detail)
                f.events.onRead = { stream -> f.events.real.snapshot(stream).let { snapshot ->
                    if (kind == "epoch") snapshot.copy(revision = snapshot.revision.copy(resetEpoch = 1))
                    else snapshot.copy(records = snapshot.records.mapIndexed { index, record -> if (index == 0) record.copy(at = record.at + 1) else record })
                } }
                throw IllegalStateException("lost ack")
            }
            assertFailsWith<TaskWorktreeJournalUnknown> { service.acceptWithLeases(owner, Input.Intent.Prepare(record(), 1, "open")) }
            assertEquals(0, f.workspace.writes)
        }
    }

    @Test fun cancellationAfterDurableIntentNeverStartsGit() = runTest {
        val f = Fixture(); val service = f.owner(); service.projection(owner, generation = 1)
        f.events.onAppend = { revision, operation, at, detail -> f.events.real.append(revision, operation, at, detail); throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { service.acceptWithLeases(owner, Input.Intent.Prepare(record(), 1, "open")) }
        assertEquals(0, f.workspace.writes)
        f.events.onAppend = { revision, operation, at, detail -> f.events.real.append(revision, operation, at, detail) }
        assertTrue(f.owner().projection(owner).unknown)
        assertEquals(0, f.workspace.writes)
    }

    @Test fun removedStreamCannotBeRecreatedByAnExistingOwner() = runTest {
        val f = Fixture(); val service = f.owner(); service.prepare()
        f.events.real.drop(service.projectionStream())
        assertFailsWith<TaskWorktreeJournalUnknown> { service.acceptWithLeases(owner, Input.Intent.Refresh("task", 1, "refresh")) }
        assertEquals(1, f.workspace.writes)
        assertTrue(f.events.real.read(service.projectionStream()).isEmpty())
    }

    @Test fun cancellationAfterAcknowledgedIntentRecordsThatThePortWasNeverCalled() = runTest {
        val f = Fixture(); val service = f.owner(); service.projection(owner, generation = 1)
        var cancelNext = true
        f.events.onAppend = { revision, operation, at, detail ->
            val record = f.events.real.append(revision, operation, at, detail)
            if (cancelNext) { cancelNext = false; currentCoroutineContext().cancel() }
            record
        }
        val operation = launch { service.acceptWithLeases(owner, Input.Intent.Prepare(record(), 1, "open")) }
        operation.join()
        assertTrue(operation.isCancelled)
        assertEquals(0, f.workspace.writes)
        val restored = f.owner().projection(owner)
        assertFalse(restored.unknown)
        assertNull(restored.task)
        assertEquals(0, f.workspace.writes)
    }

    @Test fun immutableCompletionRecoversLostTerminalFactWithoutAnyGitInspectionOrReplay() = runTest {
        val f = Fixture(); val service = f.owner(); service.projection(owner, generation = 1)
        var count = 0
        f.events.onAppend = { revision, operation, at, detail ->
            count++
            if (count > 1) throw IllegalStateException("terminal journal write lost")
            f.events.real.append(revision, operation, at, detail)
        }
        assertFailsWith<TaskWorktreeOperationUnknown> { service.acceptWithLeases(owner, Input.Intent.Prepare(record(), 1, "open")) }
        assertEquals(1, f.workspace.writes)
        assertEquals(1, f.payloads.keys("worktree-outcome:").size)
        f.events.onAppend = { revision, operation, at, detail -> f.events.real.append(revision, operation, at, detail) }
        val reopened = f.owner()
        assertTrue(reopened.projection(owner).unknown)
        val recovered = reopened.acceptWithLeases(owner, Input.Intent.Inspect("task"))
        assertFalse(recovered.unknown)
        assertEquals(TaskWorktreePhase.RUNNING, recovered.task?.phase)
        assertEquals(1, f.workspace.writes)
        assertEquals(0, f.workspace.reads)
    }

    @Test fun corruptCompletionCannotClearUnknown() = runTest {
        val f = Fixture(); val service = f.owner(); service.projection(owner, generation = 1)
        var count = 0
        f.events.onAppend = { revision, operation, at, detail ->
            if (++count > 1) throw IllegalStateException("terminal fact lost")
            f.events.real.append(revision, operation, at, detail)
        }
        assertFailsWith<TaskWorktreeOperationUnknown> { service.acceptWithLeases(owner, Input.Intent.Prepare(record(), 1, "open")) }
        f.payloads.write(f.payloads.keys("worktree-outcome:").single(), "{}")
        f.events.onAppend = { revision, operation, at, detail -> f.events.real.append(revision, operation, at, detail) }
        val reopened = f.owner(); reopened.projection(owner)
        assertFailsWith<TaskWorktreeOperationUnknown> { reopened.acceptWithLeases(owner, Input.Intent.Inspect("task")) }
        assertTrue(reopened.projection(owner).unknown)
        assertEquals(1, f.workspace.writes)
        assertEquals(0, f.workspace.reads)
    }

    @Test fun resetWaitsForAdmittedOperationAndReopensWithoutReplayingOldAuthority() = runTest {
        val f = Fixture(); val service = f.owner(); service.projection(owner, generation = 1)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        // prepareForReset lists the journal only after it has stopped admission, still under the admission lock.
        // Both calls hop to the owner's dispatcher, so without this signal the second accept can be admitted first,
        // queue on the operation's lock and wait for a release that only this body can give.
        val fenced = CompletableDeferred<Unit>()
        f.events.onStreams = { fenced.complete(Unit); f.events.real.streams() }
        f.workspace.before = { entered.complete(Unit); release.await() }
        val operation = async(Dispatchers.Default) { service.acceptWithLeases(owner, Input.Intent.Prepare(record(), 1, "open")) }
        entered.await()
        val paused = async(start = CoroutineStart.UNDISPATCHED) { service.prepareForReset() }
        fenced.await()
        assertFalse(paused.isCompleted)
        assertFailsWith<IllegalStateException> { service.acceptWithLeases(owner, Input.Intent.Prepare(record(), 1, "other")) }
        release.complete(Unit)
        operation.await(); paused.await()
        f.events.real.drop(service.projectionStream()); f.payloads.clear()
        service.resumeAfterReset()
        assertNull(service.projection(owner).task)
        assertEquals(1, f.workspace.writes)
        service.close()
        assertFailsWith<IllegalStateException> { service.resumeAfterReset() }
        assertFailsWith<IllegalStateException> { service.projection(owner) }
    }

    @Test fun cancelledPayloadOrOutcomeWriteRetainsCancellationWhenReadbackAlsoFails() = runTest {
        for (prefix in listOf("worktree-input:", "worktree-outcome:")) {
            val backing = InMemoryKeyValueStore()
            var enabled = false
            var failed = false
            val payloads = object : KeyValueStore by backing {
                override fun write(key: String, value: String) {
                    backing.write(key, value)
                    if (enabled && key.startsWith(prefix)) { failed = true; throw CancellationException("cancelled write") }
                }
                override fun read(key: String): String? {
                    if (failed) throw java.io.IOException("readback failed")
                    return backing.read(key)
                }
            }
            val workspace = Workspace()
            val service = DefaultTaskWorktreeOwner(workspace, InMemoryEventJournal(), payloads)
            service.projection(owner, generation = 1)
            enabled = true
            val cancelled = assertFailsWith<CancellationException> { service.acceptWithLeases(owner, Input.Intent.Prepare(record(), 1, "open")) }
            assertTrue(generateSequence<Throwable>(cancelled) { it.cause }.any { cause -> cause.suppressed.any { it is java.io.IOException } })
            assertEquals(if (prefix == "worktree-input:") 0 else 1, workspace.writes)
        }
    }

    @Test fun resetRollbackStillJoinsOperationWhenPauseWasCancelled() = runTest {
        // This case cancels an installed fence; UNDISPATCHED alone does not enter a separate IO dispatcher.
        val f = Fixture(); val service = f.owner(Dispatchers.Unconfined); service.projection(owner, generation = 1)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.workspace.before = { entered.complete(Unit); release.await() }
        val operation = async(Dispatchers.Default) { service.acceptWithLeases(owner, Input.Intent.Prepare(record(), 1, "open")) }
        entered.await()
        val pause = launch(start = CoroutineStart.UNDISPATCHED) { service.prepareForReset() }
        pause.cancelAndJoin()
        val rollback = async(start = CoroutineStart.UNDISPATCHED) { service.resumeAfterReset() }
        assertFalse(rollback.isCompleted, "Rollback cannot create another writer while the old one is still active")
        release.complete(Unit)
        operation.await(); rollback.await()
        assertEquals(TaskWorktreePhase.RUNNING, service.projection(owner).task?.phase)
        assertEquals(1, f.workspace.writes)
    }

    @Test fun rollbackBeforeResetAdmissionDoesNotForgetTheExistingWriter() = runTest {
        val f = Fixture(); val service = f.owner()
        service.prepare()
        val before = service.projection(owner)
        service.resumeAfterReset()
        assertEquals(before, service.projection(owner))
        assertEquals(1, f.workspace.writes)
    }

    @Test fun resetCannotEraseAnUnknownExternalOperationAndRollbackRetainsItsFence() = runTest {
        val f = Fixture(); val service = f.owner(); service.projection(owner, generation = 1)
        f.workspace.failure = IllegalStateException("external outcome unavailable")
        assertFailsWith<TaskWorktreeOperationUnknown> { service.acceptWithLeases(owner, Input.Intent.Prepare(record(), 1, "open")) }
        assertFailsWith<IllegalStateException> { service.prepareForReset() }
        assertTrue(f.events.real.read(service.projectionStream()).isNotEmpty())
        service.resumeAfterReset()
        assertTrue(service.projection(owner).unknown)
        assertFailsWith<TaskWorktreeRejected> { service.acceptWithLeases(owner, Input.Intent.BindRun("task", 2)) }
        assertEquals(1, f.workspace.writes)
    }

    @Test fun resetDiscoversUnopenedPendingJournalAfterRestartWithoutReplayingIt() = runTest {
        val f = Fixture(); val first = f.owner(); first.projection(owner, generation = 1)
        f.workspace.failure = IllegalStateException("external outcome unavailable")
        assertFailsWith<TaskWorktreeOperationUnknown> { first.acceptWithLeases(owner, Input.Intent.Prepare(record(), 1, "open")) }
        first.close()
        val restarted = f.owner()
        assertFailsWith<IllegalStateException> { restarted.prepareForReset() }
        restarted.resumeAfterReset()
        assertTrue(restarted.projection(owner).unknown)
        assertEquals(1, f.workspace.writes)
        assertEquals(0, f.workspace.reads)
    }
}
