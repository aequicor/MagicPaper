package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class SessionOrganismStoreTest {
    @Test fun manuallyArchivedRootCanAcceptANewExplicitRequestAfterUnarchiving() = runTest {
        val f = Fixture(); f.initialize()
        val id = f.organism.id
        val stopping = f.store.requestUserStop(id, "root", "manual-archive", archive = true)
        val affected = stopping.subtree("root")
        for (sessionId in affected) {
            val node = stopping.sessions.getValue(sessionId)
            f.store.observe(id, sessionId, node.generation, SessionObservedState.STOPPED)
        }
        val archived = f.store.finishStop(id, affected)
        val root = archived.sessions.getValue("root")
        assertTrue(root.archived)
        val restored = f.store.setArchiveVisibility(id, "root", root.generation, false)
        assertFalse(restored.sessions.getValue("root").acceptsWork)
        assertEquals(root.generation, restored.sessions.getValue("root").generation)
        val requested = f.store.prepareUserTurn(id, "root", "new-request")
        assertTrue(requested.sessions.getValue("root").acceptsWork)
        assertEquals(root.generation + 1, requested.sessions.getValue("root").generation)
    }

    @Test fun archiveVisibilityRejectsLiveAndStaleRunsAndRestoresWithoutReplayingWork() = runTest {
        val f = Fixture(); f.initialize()
        val id = f.organism.id
        val running = f.store.get(id).sessions.getValue("root")
        assertFalse(f.store.setArchiveVisibility(id, "root", running.generation, true).sessions.getValue("root").archived)
        f.store.observe(id, "root", running.generation, SessionObservedState.COMPLETED)
        assertFalse(f.store.setArchiveVisibility(id, "root", running.generation, true) { false }.sessions.getValue("root").archived)
        assertFalse(f.store.setArchiveVisibility(id, "root", running.generation - 1, true).sessions.getValue("root").archived)
        val archived = f.store.setArchiveVisibility(id, "root", running.generation, true)
        assertTrue(archived.sessions.getValue("root").archived)
        val reopened = DefaultSessionOrganismStore(f.storage, f.journal)
        assertTrue(reopened.get(id).sessions.getValue("root").archived)
        val restored = reopened.setArchiveVisibility(id, "root", running.generation, false)
        val node = restored.sessions.getValue("root")
        assertFalse(node.archived)
        assertEquals(running.generation, node.generation)
        assertEquals(SessionObservedState.COMPLETED, node.observed)
        assertFalse(node.acceptsWork)
        assertEquals(archived.outbox, restored.outbox)
        assertEquals(archived.results, restored.results)
    }

    private class FaultStore : KeyValueStore by InMemoryKeyValueStore() {
        private val backing = InMemoryKeyValueStore()
        var failure: String? = null
        override fun read(key: String) = backing.read(key)
        override fun write(key: String, value: String) {
            val boundary = failure.also { failure = null }
            if (boundary == "before") error("before replacement")
            backing.write(key, value)
            if (boundary == "after") error("after replacement")
        }
    }

    private class Fixture(val storage: KeyValueStore = InMemoryKeyValueStore()) {
        val journal = InMemoryEventJournal()
        val store = DefaultSessionOrganismStore(storage, journal) { 1_000 }
        lateinit var organism: SessionOrganism
        suspend fun initialize(limits: OrganismLimits = OrganismLimits(tokens = 1_000, recoveryTokens = 100)) {
            val root = CodingSession("root", "project", "Зигота", 1, planningMode = true, planId = "fixture-plan",
                planningRulesSnapshot = PlanningRulesSettings().snapshot())
            organism = store.adopt("project", root, emptyList(), limits)
            store.beginRun(organism.id, root.id)
        }
        suspend fun authority(id: String = "root"): SessionAuthority {
            val node = store.get(organism.id).sessions.getValue(id)
            return SessionAuthority("project", organism.id, id, node.generation, node.mode)
        }
        suspend fun child(id: String, parent: String = "root", tokens: Long = 100): String {
            store.command(authority(parent), id, create(parent, tokens))
            return "session-$id"
        }
        fun create(parent: String, tokens: Long) = OrganismCommand(OrganismAction.CREATE, name = "Child", tokens = tokens,
            task = SessionTask("Independent work", parent, "Verified result"))
    }

    @Test fun concurrentCreationPreservesBudgetAndActualRuntimeAdmissionEnforcesSlots() = runTest {
        val f = Fixture(); f.initialize(OrganismLimits(activeSessions = 4, tokens = 1_000, recoveryTokens = 100))
        val authority = f.authority()
        val results = (1..12).map { index -> async {
            runCatching { f.store.command(authority, "create-$index", f.create("root", 300)) }
        } }.awaitAll()
        assertEquals(12, results.count { it.isSuccess })
        val saved = f.store.get(f.organism.id)
        assertEquals(14, saved.sessions.values.count { !it.settled && !it.archived })
        assertEquals(1_000L, saved.sessions.values.sumOf { it.remainingTokens })
        assertEquals(0L, saved.sessions.getValue("root").remainingTokens)
        assertEquals(100L, saved.sessions.getValue(saved.immunityId!!).remainingTokens)
        assertTrue(saved.sessions.values.all { it.rules == saved.sessions.getValue("root").rules })
        val starts = saved.sessions.values.filter { it.kind == SessionKind.SESSION }.map { child ->
            async { runCatching { f.store.beginRun(saved.id, child.id) } }
        }.awaitAll()
        assertEquals(3, starts.count { it.isSuccess })
        assertEquals(4, f.store.get(saved.id).sessions.values.count { it.observed == SessionObservedState.RUNNING })
    }

    @Test fun commandDeduplicationSurvivesRestartAndRejectsChangedArguments() = runTest {
        val f = Fixture(); f.initialize()
        val scope = f.authority()
        val command = f.create("root", 100)
        val first = f.store.command(scope, "same", command)
        val restarted = DefaultSessionOrganismStore(f.storage, f.journal) { 1_000 }
        assertEquals(first, restarted.command(scope, "same", command))
        assertFailsWith<IllegalArgumentException> { restarted.command(scope, "same", command.copy(tokens = 200)) }
        assertEquals(1, restarted.get(first.id).operations.size)
    }

    @Test fun receiptCannotBeReplayedByAnotherActorOrMode() = runTest {
        val f = Fixture(); f.initialize()
        val first = f.child("a"); val second = f.child("b")
        val command = OrganismCommand(OrganismAction.SIGNAL, target = "root", reason = "stalled request")
        val a = f.authority(first)
        f.store.command(a, "signal", command)
        assertFailsWith<IllegalArgumentException> { f.store.command(f.authority(second), "signal", command) }
        assertFailsWith<IllegalArgumentException> { f.store.command(a.copy(mode = CodingInteractionMode.CODE), "signal", command) }
    }

    @Test fun failedSaveNeverPartiallyReservesResourcesAndAfterSaveFailureReconciles() = runTest {
        val storage = FaultStore(); val f = Fixture(storage); val journal = f.journal; f.initialize()
        val before = f.store.get(f.organism.id)
        storage.failure = "before"
        assertFailsWith<IllegalStateException> { f.store.command(f.authority(), "create", f.create("root", 100)) }
        assertEquals(before, f.store.get(f.organism.id))
        assertFails { f.store.command(f.authority(), "create", f.create("root", 100)) }
        val reopened = DefaultSessionOrganismStore(storage, journal) { 1_000 }
        storage.failure = "after"
        val saved = reopened.command(f.authority(), "create", f.create("root", 100))
        assertEquals(1, saved.operations.size)
        assertEquals(800L, saved.sessions.getValue("root").remainingTokens)
        assertEquals(saved, DefaultSessionOrganismStore(storage, journal) { 1_000 }.get(saved.id))
    }

    @Test fun outboxPersistsBeforeDeliveryAndRequiresOrderedExplicitAcknowledgements() = runTest {
        val storage = FaultStore(); val f = Fixture(storage); val journal = f.journal; f.initialize()
        val child = f.child("a")
        storage.failure = "after"
        f.store.command(f.authority(), "message-1", OrganismCommand(OrganismAction.SEND, target = child,
            packet = SessionContextPacket("Context", sourceVersion = "sha", ruleVersion = "default:1", summarized = true, omissions = "logs")))
        f.store.command(f.authority(), "message-2", OrganismCommand(OrganismAction.SEND, target = child,
            packet = SessionContextPacket("Next context")))
        val restarted = DefaultSessionOrganismStore(storage, journal) { 1_000 }
        val pending = restarted.get(f.organism.id).outbox
        assertEquals(2, pending.size)
        assertTrue(pending.all { it.state == SessionDeliveryState.ACCEPTED && it.origin == MessageOrigin.SESSION })
        assertEquals(listOf("root", child), pending.first().route)
        assertTrue(pending.first().packet.summarized)
        assertFailsWith<IllegalArgumentException> { restarted.acknowledge(f.organism.id, "message-2", child, 1, false) }
        assertFailsWith<IllegalArgumentException> { restarted.acknowledge(f.organism.id, "message-1", child, 1, true) }
        restarted.acknowledge(f.organism.id, "message-1", child, 1, false)
        restarted.acknowledge(f.organism.id, "message-1", child, 1, true)
        restarted.acknowledge(f.organism.id, "message-1", child, 1, false)
        assertEquals(SessionDeliveryState.PROCESSED, restarted.get(f.organism.id).outbox.first().state)
        restarted.acknowledge(f.organism.id, "message-2", child, 1, false)
    }

    @Test fun siblingsCannotSendOrManageAndNestedChildrenUseImmediateParent() = runTest {
        val f = Fixture(); f.initialize()
        val a = f.child("a", tokens = 300); val b = f.child("b")
        val nested = f.child("nested", a)
        assertFailsWith<IllegalArgumentException> { f.store.command(f.authority(a), "sibling-message", OrganismCommand(OrganismAction.SEND,
            target = b, packet = SessionContextPacket("forbidden"))) }
        assertFailsWith<IllegalArgumentException> { f.store.command(f.authority(a), "sibling-stop", OrganismCommand(OrganismAction.STOP, target = b)) }
        assertFailsWith<IllegalArgumentException> { f.store.command(f.authority(), "grandchild-stop", OrganismCommand(OrganismAction.STOP, target = nested)) }
        f.store.command(f.authority(nested), "parent-message", OrganismCommand(OrganismAction.SEND, target = a, packet = SessionContextPacket("result")))
        assertEquals(listOf(nested, a), f.store.get(f.organism.id).outbox.single().route)
    }

    @Test fun immunitySignalSurvivesParentQuarantineButOrdinaryWorkDoesNot() = runTest {
        val f = Fixture(); f.initialize()
        val a = f.child("a", tokens = 300); val nested = f.child("nested", a)
        val scope = f.authority(nested)
        f.store.command(f.authority(), "quarantine", OrganismCommand(OrganismAction.QUARANTINE, target = a))
        assertFailsWith<IllegalArgumentException> { f.store.check(scope) }
        f.store.command(scope, "complaint", OrganismCommand(OrganismAction.SIGNAL, target = a, reason = "Unanswered operation 123"))
        val saved = f.store.get(f.organism.id)
        assertEquals(nested, saved.signals.single().sender)
        assertEquals(SessionDesiredState.QUARANTINE, saved.sessions.getValue(a).desired)
        assertFalse(saved.sessions.getValue(a).archived)
    }

    @Test fun subtreeStopCancelsOutboxAndRequiresChildConfirmationBeforeParentSettlement() = runTest {
        val f = Fixture(); f.initialize()
        val a = f.child("a", tokens = 300); val nested = f.child("nested", a)
        f.store.command(f.authority(a), "queued", OrganismCommand(OrganismAction.SEND, target = nested, packet = SessionContextPacket("pending")))
        f.store.command(f.authority(), "stop", OrganismCommand(OrganismAction.ARCHIVE, target = a))
        val stopping = f.store.get(f.organism.id)
        assertEquals(SessionOperationState.ACCEPTED, stopping.operations.getValue("stop").state)
        assertEquals(SessionDeliveryState.CANCELLED, stopping.outbox.single().state)
        assertFalse(stopping.sessions.getValue(a).archived)
        for (terminal in listOf(SessionObservedState.COMPLETED, SessionObservedState.STOPPED, SessionObservedState.FAILED)) {
            assertFailsWith<IllegalArgumentException> { f.store.observe(stopping.id, a, 1, terminal) }
        }
        f.store.observe(stopping.id, nested, 1, SessionObservedState.STOPPED)
        f.store.observe(stopping.id, a, 1, SessionObservedState.STOPPED)
        val stopped = f.store.finishStop(stopping.id, setOf(a, nested))
        assertTrue(stopped.sessions.getValue(a).archived)
        assertTrue(stopped.sessions.getValue(nested).archived)
        assertEquals(SessionOperationState.SUCCEEDED, stopped.operations.getValue("stop").state)
        assertEquals(900L, stopped.sessions.getValue("root").remainingTokens)
    }

    /** The coding projection ends a run and its conversation by `previousGeneration`, so only a restart may name one. */
    @Test fun aGenerationARunMovesOnNamesNoPreviousGenerationWhileARestartDoes() = runTest {
        val f = Fixture(); f.initialize()
        val crashed = f.store.get(f.organism.id).sessions.getValue("root")
        // Found running after a crash, then resumed by the user's next turn: a restart.
        f.store.observe(f.organism.id, "root", crashed.generation, SessionObservedState.UNKNOWN)
        val resumed = f.store.prepareUserTurn(f.organism.id, "root", "resume").sessions.getValue("root")
        assertEquals(crashed.generation + 1, resumed.generation)
        assertEquals(crashed.generation, resumed.previousGeneration)
        // Its first run begins the resumed generation itself and keeps the restart on record.
        val first = f.store.beginRun(f.organism.id, "root")
        assertEquals(resumed.generation, first.generation)
        assertEquals(crashed.generation, first.previousGeneration)
        // A later turn moves the generation on by running.
        f.store.observe(f.organism.id, "root", first.generation, SessionObservedState.COMPLETED)
        val second = f.store.beginRun(f.organism.id, "root")
        assertEquals(first.generation + 1, second.generation)
        assertNull(second.previousGeneration)
    }

    @Test fun restorationRevokesOldGenerationAndPreservesBudgetAndLineage() = runTest {
        val f = Fixture(); f.initialize(OrganismLimits(tokens = 1_000, recoveryTokens = 100, retries = 1))
        val child = f.child("a")
        val oldScope = f.authority(child)
        f.store.observe(f.organism.id, child, oldScope.generation, SessionObservedState.STOPPED)
        f.store.command(f.authority(), "restore", OrganismCommand(OrganismAction.RESTORE, target = child,
            tokens = 80, reason = "Task and source version rechecked"))
        val restored = f.store.get(f.organism.id).sessions.getValue(child)
        assertEquals(oldScope.generation + 1, restored.generation)
        assertEquals(oldScope.generation, restored.previousGeneration)
        assertEquals("root", restored.originParentId)
        assertFailsWith<IllegalArgumentException> { f.store.check(oldScope) }
        assertFailsWith<IllegalArgumentException> { f.store.observe(f.organism.id, child, oldScope.generation, SessionObservedState.COMPLETED) }
        f.store.observe(f.organism.id, child, restored.generation, SessionObservedState.STOPPED)
        assertFailsWith<IllegalArgumentException> { f.store.command(f.authority(), "restore-again", OrganismCommand(OrganismAction.RESTORE,
            target = child, tokens = 80, reason = "retry")) }
        assertEquals(1_000L, f.store.get(f.organism.id).sessions.values.sumOf { it.remainingTokens + it.spentTokens })
    }

    @Test fun recoveryCannotAuthorizeToolsUntilRuntimeOutcomeIsReconciled() = runTest {
        val f = Fixture(); f.initialize()
        val scope = f.authority()
        val recovered = f.store.recover(f.organism.id)
        assertEquals(SessionObservedState.UNKNOWN, recovered.sessions.getValue("root").observed)
        assertFailsWith<IllegalArgumentException> { f.store.check(scope) }
        // A restart alone proves neither native cleanup nor the outcome of tool effects.
        assertFailsWith<IllegalArgumentException> { f.store.beginRun(f.organism.id, "root") }
        val interrupted = recovered.sessions.getValue("root")
        f.store.reconcileInterruptedRun(f.organism.id, "root", interrupted.generation, interrupted.version)
        val reopened = f.store.beginRun(f.organism.id, "root")
        assertEquals(SessionObservedState.RUNNING, reopened.observed)
        assertTrue(reopened.generation > scope.generation)
    }

    @Test fun independentFailureIsIsolatedAndExplicitCancelPolicyStopsSiblingSubtrees() = runTest {
        for (policy in SessionFailurePolicy.entries) {
            val f = Fixture(); f.initialize()
            val a = f.child("a"); val b = f.child("b")
            val current = f.store.get(f.organism.id)
            val configured = current.copy(sessions = current.sessions + ("root" to current.sessions.getValue("root").copy(failurePolicy = policy)))
            f.storage.write("session-organism-${current.id}", Json.encodeToString(configured))
            val imported = DefaultSessionOrganismStore(f.storage, InMemoryEventJournal())
            val failed = imported.observe(current.id, a, 1, SessionObservedState.FAILED)
            assertEquals(if (policy == SessionFailurePolicy.ISOLATE) SessionDesiredState.RUN else SessionDesiredState.STOP,
                failed.sessions.getValue(b).desired)
        }
    }
}
