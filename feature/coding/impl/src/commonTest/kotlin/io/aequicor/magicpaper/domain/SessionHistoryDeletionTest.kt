package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.SessionOrganismStore
import io.aequicor.magicpaper.domain.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class SessionHistoryDeletionTest {
    private suspend fun fixture(): SessionOrganismTestFixture = SessionOrganismTestFixture().also {
        it.initialize(); it.service.startChild = { _, _ -> }; installConfirmedStop(it)
    }

    private fun installConfirmedStop(f: SessionOrganismTestFixture, observed: (Set<String>) -> Unit = {}) {
        f.service.stopSubtree = { ids ->
            observed(ids)
            val initial = f.store.get(f.root.organismId!!)
            fun depth(id: String): Int = initial.sessions.getValue(id).lifecycleParentId?.let { 1 + depth(it) } ?: 0
            ids.sortedByDescending(::depth).forEach { id ->
                val node = f.store.get(initial.id).sessions.getValue(id)
                f.store.observe(initial.id, id, node.generation, SessionObservedState.STOPPED)
            }
        }
    }

    private fun restarted(f: SessionOrganismTestFixture) = SessionOrganismService(SessionOrganismStore(f.storage) { 1_000 }, f.projects, f.settings)

    @Test fun deletingSubtreeKeepsSiblingAndIndependentImmunityAcrossRestart() = runTest {
        val f = fixture(); f.create("child"); f.create("sibling")
        f.service.execute(f.context("session-child"), "nested", "session.create", Json.encodeToJsonElement(
            SessionCreateArgs("Nested", "Inspect", "Verified evidence", 100)).jsonObject)
        f.send("packet", "session-child")
        val auditBefore = f.store.get(f.root.organismId!!).audit
        val removed = f.service.deleteHistory(f.project.id, "session-child")
        assertEquals(setOf("session-child", "session-nested"), removed)
        val deleted = f.store.get(f.root.organismId!!)
        assertEquals(removed, deleted.historyDeletedIds)
        assertNull(deleted.deletedAt)
        assertTrue(deleted.audit.containsAll(auditBefore))
        assertEquals(SessionDeliveryState.CANCELLED, deleted.outbox.single().state)
        assertEquals(setOf("root", "session-sibling", deleted.immunityId), f.projects.sessions(f.project.id).map { it.id }.toSet())
        val resumed = restarted(f)
        try { resumed.recover() } finally { resumed.shutdown() }
        assertTrue(f.projects.messages(f.project.id, "session-child").isEmpty())
        assertTrue(f.projects.sessions(f.project.id).none { it.id in removed })
        assertFailsWith<IllegalArgumentException> {
            f.store.command(SessionAuthority(f.project.id, deleted.id, "root", deleted.sessions.getValue("root").generation,
                CodingInteractionMode.RESEARCH), "cannot-restore-deleted", OrganismCommand(OrganismAction.RESTORE, "session-child", reason = "retry", tokens = 100))
        }
    }

    @Test fun deletingRootAlsoDisposesImmunityAndProjectRecoveryCannotResurrectIt() = runTest {
        val f = fixture(); f.create("child"); f.send("packet", "session-child")
        val before = f.store.get(f.root.organismId!!)
        assertEquals(before.sessions.keys, f.service.deleteHistory(f.project.id, f.root.id))
        val deleted = f.store.get(before.id)
        assertNotNull(deleted.deletedAt)
        assertEquals(before.sessions.keys, deleted.historyDeletedIds)
        assertTrue(f.projects.sessions(f.project.id).isEmpty())
        f.projects.delete(f.project.id)
        val resumed = restarted(f)
        try { resumed.recover(); resumed.project(before); resumed.deliver(before.id) } finally { resumed.shutdown() }
        assertTrue(f.projects.all().isEmpty())
        assertTrue(f.projects.sessions(f.project.id).isEmpty())
        assertEquals(SessionDeliveryState.CANCELLED, resumed.store.get(before.id).outbox.single().state)
    }

    @Test fun missingLegacyProjectOrRootIsNotRecreatedByAggregateDiscovery() = runTest {
        for (deleteProject in listOf(false, true)) {
            val f = fixture()
            if (deleteProject) f.projects.delete(f.project.id) else f.projects.deleteSession(f.project.id, f.root.id)
            val before = f.projects.sessions(f.project.id)
            val resumed = restarted(f)
            try { resumed.recover() } finally { resumed.shutdown() }
            assertEquals(before, f.projects.sessions(f.project.id))
            assertTrue(f.projects.sessions(f.project.id).none { it.id == f.root.id })
        }
    }

    @Test fun projectionDeletionFailureReplaysTombstoneWithoutRestoringLogs() = runTest {
        val backing = SessionOrganismTestFixture()
        var fail = false
        val projects = object : CodingProjectRepository by backing.projects {
            override suspend fun deleteSession(projectId: String, sessionId: String) {
                if (fail) { fail = false; error("delete projection interrupted") }
                backing.projects.deleteSession(projectId, sessionId)
            }
        }
        val f = SessionOrganismTestFixture(projects, backing.storage); f.initialize()
        f.service.startChild = { _, _ -> }; installConfirmedStop(f)
        f.create("child"); f.send("packet", "session-child")
        fail = true
        assertFailsWith<IllegalStateException> { f.service.deleteHistory(f.project.id) }
        val tombstone = f.store.get(f.root.organismId!!)
        assertNotNull(tombstone.deletedAt)
        assertTrue(f.projects.sessions(f.project.id).isNotEmpty())
        val resumed = restarted(f)
        try { resumed.recover(); resumed.project(tombstone); resumed.deliver(tombstone.id) } finally { resumed.shutdown() }
        assertTrue(f.projects.sessions(f.project.id).isEmpty())
        tombstone.historyDeletedIds.forEach { assertTrue(f.projects.messages(f.project.id, it).isEmpty()) }
        assertEquals(1, resumed.store.get(tombstone.id).audit.count { it.action == "DELETE_HISTORY" })
    }

    @Test fun failedLegacyCleanupStillStopsEveryOwnerButKeepsHistoryUntilRetry() = runTest {
        val f = fixture(); f.create("child"); f.send("packet", "session-child")
        var stopped = emptySet<String>()
        installConfirmedStop(f) { stopped = it }
        assertFailsWith<IllegalStateException> { f.service.deleteHistory(f.project.id) { error("legacy cleanup failed") } }
        val saved = f.store.get(f.root.organismId!!)
        assertEquals(saved.sessions.keys, stopped)
        assertTrue(saved.sessions.values.all { it.settled })
        assertTrue(saved.historyDeletedIds.isEmpty())
        assertTrue(f.projects.messages(f.project.id, "session-child").isNotEmpty())
        f.service.deleteHistory(f.project.id)
        assertTrue(f.projects.sessions(f.project.id).isEmpty())
    }

    @Test fun unconfirmedStopCannotCommitHistoryDeletion() = runTest {
        val f = fixture(); f.create("child")
        assertFailsWith<IllegalArgumentException> { f.store.deleteHistoryByUser(f.root.organismId!!, null) }
        f.service.stopSubtree = { ids ->
            val saved = f.store.get(f.root.organismId!!)
            ids.forEach { id -> f.store.observe(saved.id, id, saved.sessions.getValue(id).generation, SessionObservedState.UNKNOWN) }
        }
        assertFailsWith<IllegalArgumentException> { f.service.deleteHistory(f.project.id) }
        assertTrue(f.store.get(f.root.organismId!!).historyDeletedIds.isEmpty())
        assertTrue(f.projects.sessions(f.project.id).any { it.id == f.root.id })
    }

    @Test fun deletionWaitsForActualChildCleanupAndRejectsNewProjectAdmission() = runTest { withContext(Dispatchers.Default) {
        val f = SessionOrganismTestFixture(); f.initialize()
        val started = CompletableDeferred<Unit>(); val cleaning = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val native = object : CodingRuntime {
            override val supported = true
            override val rootPath = "/fixture"
            override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
            override fun ensureReady() = flowOf(RuntimeStatus(RuntimePhase.READY))
            override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow<CodingEvent> {
                started.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
            }
            override fun abort(sessionId: String) = Unit
            override fun abortAll() = Unit
            override suspend fun uninstall() = Unit
        }
        val tree = SessionTreeRuntime(f.service, f.projects, f.profiles, f.settings, clock = { 1_000 })
        tree.runtime = ToolEnabledCodingRuntime(native, ToolHost(MemoryToolReceiptStore()), tree)
        tree.cancelQuestions = { error("question cleanup write failed") }
        val rootJob = launch { tree.withScope(f.root) { f.create("child"); awaitCancellation() } }
        var deletion: Deferred<Result<Set<String>>>? = null
        try {
            withTimeout(5_000) { started.await() }
            val removing = async { runCatching { f.service.deleteHistory(f.project.id) } }
            deletion = removing
            withTimeout(5_000) { cleaning.await() }
            assertFalse(removing.isCompleted)
            assertTrue(f.store.get(f.root.organismId!!).historyDeletedIds.isEmpty())
            assertTrue(f.projects.sessions(f.project.id).any { it.id == f.root.id })
            assertFailsWith<IllegalArgumentException> { f.service.ensure(f.root.copy(id = "new-root", organismId = null)) }
            release.complete(Unit)
            assertIs<IllegalArgumentException>(withTimeout(5_000) { removing.await() }.exceptionOrNull())
            assertTrue(rootJob.isCompleted)
            assertNull(f.store.get(f.root.organismId!!).deletedAt)
            assertTrue(f.projects.sessions(f.project.id).isNotEmpty())
            // Reconciliation must retry the failed questionnaire cleanup as well as native exit.
            assertFailsWith<IllegalArgumentException> { f.service.deleteHistory(f.project.id) }
            assertNull(f.store.get(f.root.organismId!!).deletedAt)
            tree.cancelQuestions = {}
            val ids = withTimeout(5_000) { f.service.deleteHistory(f.project.id) }
            assertTrue("session-child" in ids && "root-immunity" in ids)
            assertTrue(f.projects.sessions(f.project.id).isEmpty())
            assertNotNull(f.store.get(f.root.organismId!!).deletedAt)
        } finally { release.complete(Unit); rootJob.cancelAndJoin(); deletion?.cancelAndJoin(); tree.shutdown() }
    } }
}
