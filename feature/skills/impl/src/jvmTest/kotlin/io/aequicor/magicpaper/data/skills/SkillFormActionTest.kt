package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.plugins.builtin.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SkillFormActionTest {
    @Test fun actionSurvivesPanelDisposalAndResetDrainsAcceptedClearBeforeRevocation() = runTest {
        val repository = InMemoryDraftRepository()
        val forms = SkillsFormDrafts(repository, backgroundScope)
        val owner = forms.project("project")
        val session = owner.draft
        session.awaitSaved()
        owner.update { it.copy(pending = emptyMap(), generation = 4, trustedTextConsent = true) }
        val version = session.state.value.version
        val gate = CompletableDeferred<Unit>()
        val operation = forms.action("project", "ProjectSkillsPanel", "project")
        var commits = 0
        val panelJob = SupervisorJob()
        CoroutineScope(coroutineContext + panelJob).launch {
            operation.launch { gate.await(); commits++; operation.accepted { session.awaitSaved(); session.clearIfUnchanged(version) }; "accepted" }
        }.join()
        panelJob.cancel()
        val resetting = backgroundScope.async { forms.prepareForReset() }
        runCurrent()
        assertFalse(resetting.isCompleted)
        operation.launch { commits++; "stale callback" }
        gate.complete(Unit)
        resetting.await()
        assertEquals(1, commits)
        assertNull(repository.load("skills:project:project:selection"))
        assertFalse(operation.state.value.busy)
        forms.resumeAfterReset()
        operation.launch { commits++; "callback from old generation" }
        owner.update { it.copy(trustedTextConsent = true) }
        assertEquals(1, commits)
        assertNull(repository.load("skills:project:project:selection"))
    }

    @Test fun failedAcceptedCleanupNeverRepeatsMutationAndRetryOnlyClearsCapturedDraft() = runTest {
        val delegate = InMemoryDraftRepository()
        var failClear = true
        val repository = object : DraftRepository by delegate {
            override suspend fun deleteIfOwned(key: String, revision: Long, ownerEpoch: Long, resetEpoch: Long): Boolean {
                if (failClear) throw StorageException("fixture clear", StorageException.Kind.WRITE)
                return delegate.deleteIfOwned(key, revision, ownerEpoch, resetEpoch)
            }
        }
        val forms = SkillsFormDrafts(repository, backgroundScope)
        val session = forms.text().draft
        session.awaitSaved(); session.update { it.copy(readyText = "accepted text") }; session.awaitSaved()
        val version = session.state.value.version
        val operation = forms.action("text", "LocalSkillsPlugin")
        var commits = 0
        operation.launch { commits++; operation.accepted { session.awaitSaved(); session.clearIfUnchanged(version) }; "done" }
        runCurrent()
        assertEquals(1, commits)
        assertTrue(operation.state.value.cleanupPending)
        assertContains(operation.state.value.notice, "Действие выполнено")
        operation.launch { commits++; "must not repeat install" }
        assertEquals(1, commits)
        // New input is protected even if cleanup is retried after the accepted operation.
        session.update { it.copy(readyText = "newer input") }
        failClear = false
        operation.retryCleanup(); operation.awaitIdle()
        assertFalse(operation.state.value.cleanupPending)
        assertEquals(1, commits)
        assertEquals("newer input", session.state.value.value.readyText)
        assertNotNull(delegate.load("skills:library:text"))
    }

    @Test fun resetDrainsEveryStartedActionEvenWhenAnEarlierAcceptedCleanupFails() = runTest {
        val forms = SkillsFormDrafts(InMemoryDraftRepository(), backgroundScope)
        val first = forms.action("first", "LocalSkillsPlugin")
        val second = forms.action("second", "ProjectSkillsPanel", "project")
        val gate = CompletableDeferred<Unit>()
        var secondFinished = false
        first.launch { first.accepted { throw StorageException("fixture cleanup", StorageException.Kind.WRITE) }; "accepted" }
        second.launch { gate.await(); secondFinished = true; "finished" }
        runCurrent()
        val result = backgroundScope.async { runCatching { forms.prepareForReset() } }
        runCurrent()
        assertFalse(result.isCompleted)
        gate.complete(Unit)
        assertTrue(result.await().isFailure)
        assertTrue(secondFinished)
    }

    @Test fun removedProjectRejectsCapturedActionCallbacks() = runTest {
        val forms = SkillsFormDrafts(InMemoryDraftRepository(), backgroundScope)
        val operation = forms.action("project", "ProjectSkillsPanel", "project")
        forms.project("project").update { it.copy(catalogOpen = true) }
        forms.removeProject("project")
        var calls = 0
        operation.launch { calls++; "must not bind removed project" }
        operation.awaitIdle()
        assertEquals(0, calls)
        assertFalse(forms.available("project"))
    }
}
