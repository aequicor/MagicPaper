package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CodingFormDraftsTest {
    private val session = CodingSession("session/with/slash", "project", "Original", 1)
    private val plan = Plan("plan", "project", "Goal", runId = "run")
    private fun rule(run: String = "run") = ScheduledMessage("rule", "plan", run, session.id,
        MessageTrigger(MessageTriggerKind.AT_TIME, at = 100), session.id, null, "Original rule", 1, deliveryId = "delivery")

    @Test fun reopenRestoresInvalidInputWithoutExecutingAndRunIdentityKeepsDraftsSeparate() = runTest {
        val repository = InMemoryDraftRepository()
        val first = CodingFormDrafts(repository, backgroundScope)
        first.rename(session).update("   ")
        first.schedule(session, plan, rule()).update("unfinished edit")
        first.flush(); first.revoke()
        val reopened = CodingFormDrafts(repository, backgroundScope)
        val rename = reopened.rename(session)
        val edit = reopened.schedule(session, plan, rule())
        val nextRun = reopened.schedule(session, plan.copy(runId = "next"), rule("next"))
        runCurrent()
        assertEquals("   ", rename.draft.state.value.value.text)
        assertEquals("unfinished edit", edit.draft.state.value.value.text)
        assertEquals("", nextRun.draft.state.value.value.text)
        assertEquals(0L, edit.state.value.completed)
    }

    @Test fun clearWaitsForDurableAcceptanceAndPreservesNewerTyping() = runTest {
        val repository = InMemoryDraftRepository()
        val owner = CodingFormDrafts(repository, backgroundScope)
        val form = owner.schedule(session, plan, rule())
        runCurrent(); form.update("captured")
        val accepted = CompletableDeferred<Boolean>()
        var submitted = ""
        form.submit { text, _, _ -> submitted = text; accepted.await() }
        runCurrent()
        assertEquals("captured", submitted)
        assertNotNull(repository.load(repository.keys("coding-form/").single()))
        form.update("typed during send")
        accepted.complete(true); runCurrent()
        assertEquals("typed during send", form.draft.state.value.value.text)
        assertEquals(0L, form.state.value.completed)
        owner.flush(); owner.revoke()
        assertEquals("typed during send", CodingFormDrafts(repository, backgroundScope).schedule(session, plan, rule()).also { runCurrent() }.draft.state.value.value.text)
    }

    @Test fun failedCleanupRetriesOnlyCleanupAndNeverSendsAgain() = runTest {
        val backing = InMemoryDraftRepository()
        var failClear = true
        val repository = object : DraftRepository by backing {
            override suspend fun deleteIfOwned(key: String, revision: Long, ownerEpoch: Long, resetEpoch: Long): Boolean {
                if (failClear) throw StorageException("test clear", StorageException.Kind.WRITE)
                return backing.deleteIfOwned(key, revision, ownerEpoch, resetEpoch)
            }
        }
        val form = CodingFormDrafts(repository, backgroundScope).schedule(session, plan, rule())
        runCurrent(); form.update("edit")
        var sends = 0
        form.submit { _, _, _ -> sends++; true }; runCurrent()
        assertEquals(1, sends); assertNotNull(form.state.value.error)
        assertEquals("edit", form.draft.state.value.value.text)
        failClear = false
        form.submit { _, _, _ -> sends++; true }; runCurrent()
        assertEquals(1, sends)
        assertEquals("", form.draft.state.value.value.text)
        assertEquals(1L, form.state.value.completed)
        assertTrue(backing.keys("coding-form/").isEmpty())
    }

    @Test fun failedActionPreservesDraftAndExplicitDiscardClearsIt() = runTest {
        val repository = InMemoryDraftRepository()
        val form = CodingFormDrafts(repository, backgroundScope).rename(session)
        runCurrent(); form.update("new name")
        form.submit { _, _, _ -> false }; runCurrent()
        assertNotNull(form.state.value.error)
        assertEquals("new name", form.draft.state.value.value.text)
        form.discard(session.name); runCurrent()
        assertEquals(session.name, form.draft.state.value.value.text)
        assertTrue(repository.keys("coding-form/").isEmpty())
    }

    @Test fun deletionClearsUnopenedEntityDraftsAndRevokesOldWriters() = runTest {
        val repository = InMemoryDraftRepository()
        val owner = CodingFormDrafts(repository, backgroundScope)
        val old = owner.rename(session)
        old.update("pending")
        val sibling = owner.rename(session.copy(id = "sibling"))
        sibling.update("keep")
        owner.flush()
        val reopened = CodingFormDrafts(repository, backgroundScope)
        reopened.remove(session.projectId, setOf(session.id))
        old.update("late writer"); runCurrent()
        assertNotNull(old.draft.state.value.error)
        assertEquals(1, repository.keys("coding-form/").size)
        reopened.remove(session.projectId)
        val stale = reopened.rename(session)
        stale.update("must not resurrect")
        stale.submit { _, _, _ -> fail("Deleted entity cannot execute") }
        runCurrent()
        assertFalse(stale.state.value.available)
        assertTrue(repository.keys("coding-form/").isEmpty())
    }

    @Test fun operationIdentitySurvivesRestartUntilAcceptedDraftCanBeCleared() = runTest {
        val backing = InMemoryDraftRepository()
        var failClear = true
        val repository = object : DraftRepository by backing {
            override suspend fun deleteIfOwned(key: String, revision: Long, ownerEpoch: Long, resetEpoch: Long): Boolean {
                if (failClear) throw StorageException("clear", StorageException.Kind.WRITE)
                return backing.deleteIfOwned(key, revision, ownerEpoch, resetEpoch)
            }
        }
        val owner = CodingFormDrafts(repository, backgroundScope)
        val form = owner.schedule(session, plan, rule())
        runCurrent(); form.update("edit")
        val receipts = mutableSetOf<String>()
        form.submit { _, operation, _ -> receipts.add(operation); true }; runCurrent()
        assertEquals(1, receipts.size)
        owner.revoke()
        val reopened = CodingFormDrafts(repository, backgroundScope).schedule(session, plan, rule())
        runCurrent(); failClear = false
        reopened.submit { _, operation, _ ->
            assertTrue(operation in receipts, "A retry must reconcile the same durable command")
            receipts.add(operation); true
        }; runCurrent()
        assertEquals(1, receipts.size)
        assertTrue(backing.keys("coding-form/").isEmpty())
    }

    @Test fun postCommitCleanupRetryCompletesWithoutRepeatingCommand() = runTest {
        val backing = InMemoryDraftRepository()
        var failCleanup = true
        val repository = object : DraftRepository by backing {
            override suspend fun deleteIfOwned(key: String, revision: Long, ownerEpoch: Long, resetEpoch: Long): Boolean {
                val result = backing.deleteIfOwned(key, revision, ownerEpoch, resetEpoch)
                if (failCleanup) throw StorageException("cleanup", StorageException.Kind.CLEANUP, committed = true)
                return result
            }
            override suspend fun retryCleanup() { check(!failCleanup) }
        }
        val form = CodingFormDrafts(repository, backgroundScope).schedule(session, plan, rule())
        runCurrent(); form.update("edit")
        var sends = 0
        form.submit { _, _, _ -> sends++; true }; runCurrent()
        assertEquals("", form.draft.state.value.value.text)
        assertNotNull(form.state.value.error)
        assertEquals(0L, form.state.value.completed)
        failCleanup = false; form.retry(); runCurrent()
        assertEquals(1, sends)
        assertNull(form.state.value.error)
        assertEquals(1L, form.state.value.completed)
    }

    @Test fun typingDuringSuspendedClearDoesNotCloseEditor() = runTest {
        val backing = InMemoryDraftRepository()
        val clearStarted = CompletableDeferred<Unit>()
        val continueClear = CompletableDeferred<Unit>()
        val repository = object : DraftRepository by backing {
            override suspend fun deleteIfOwned(key: String, revision: Long, ownerEpoch: Long, resetEpoch: Long): Boolean {
                clearStarted.complete(Unit); continueClear.await()
                return backing.deleteIfOwned(key, revision, ownerEpoch, resetEpoch)
            }
        }
        val form = CodingFormDrafts(repository, backgroundScope).schedule(session, plan, rule())
        runCurrent(); form.update("edit")
        form.submit { _, _, _ -> true }; runCurrent()
        clearStarted.await(); form.update("typed during clear")
        continueClear.complete(Unit); runCurrent()
        assertEquals("typed during clear", form.draft.state.value.value.text)
        assertEquals(0L, form.state.value.completed)
        form.draft.awaitSaved()
        assertEquals(1, backing.keys("coding-form/").size)
    }

    @Test fun shutdownFlushAttemptsEveryFormAfterOneWriterFails() = runTest {
        val backing = InMemoryDraftRepository()
        val attempts = mutableListOf<String>()
        var failing = false
        val repository = object : DraftRepository by backing {
            override suspend fun save(draft: DraftRecord): Boolean {
                attempts += draft.key
                if (failing && draft.key.endsWith("rename")) throw StorageException("save", StorageException.Kind.WRITE)
                return backing.save(draft)
            }
        }
        val owner = CodingFormDrafts(repository, backgroundScope)
        val broken = owner.rename(session)
        val healthy = owner.schedule(session, plan, rule())
        runCurrent(); failing = true
        broken.update("unsaved rename"); healthy.update("must survive")
        assertFailsWith<StorageException> { owner.flush() }
        owner.revoke()
        assertTrue(attempts.any { "/schedule/" in it })
        val restored = CodingFormDrafts(backing, backgroundScope).schedule(session, plan, rule())
        runCurrent()
        assertEquals("must survive", restored.draft.state.value.value.text)
    }

    @Test fun nameTypedDuringClearUsesTheAcceptedNameAsItsBaseline() = runTest {
        val backing = InMemoryDraftRepository()
        val clearStarted = CompletableDeferred<Unit>()
        val continueClear = CompletableDeferred<Unit>()
        val repository = object : DraftRepository by backing {
            override suspend fun deleteIfOwned(key: String, revision: Long, ownerEpoch: Long, resetEpoch: Long): Boolean {
                clearStarted.complete(Unit); continueClear.await()
                return backing.deleteIfOwned(key, revision, ownerEpoch, resetEpoch)
            }
        }
        val form = CodingFormDrafts(repository, backgroundScope).rename(session)
        runCurrent(); form.update("Saved name")
        form.submit(replacement = { it }) { _, _, baseline -> assertEquals(session.name, baseline); true }
        runCurrent(); clearStarted.await()
        form.update("Next name"); continueClear.complete(Unit); runCurrent()
        assertEquals("Next name", form.draft.state.value.value.text)
        assertEquals("Saved name", form.draft.state.value.value.baseline)
        assertEquals(0L, form.state.value.completed)
    }
}
