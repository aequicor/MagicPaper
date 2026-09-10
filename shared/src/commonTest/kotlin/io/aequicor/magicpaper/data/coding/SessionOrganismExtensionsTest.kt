package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SessionOrganismExtensionsTest {
    private class Fixture {
        val storage = InMemoryKeyValueStore()
        val store = SessionOrganismStore(storage) { 1_000 }
        val rules = PlanningRulesSettings().snapshot()
        lateinit var organism: SessionOrganism
        suspend fun init() {
            organism = store.adopt("p", CodingSession("root", "p", "Root", 1, researchMode = true,
                planningRulesSnapshot = rules), emptyList())
            store.beginRun(organism.id, "root")
        }
        suspend fun scope(id: String = "root"): SessionAuthority {
            val node = store.get(organism.id).sessions.getValue(id)
            return SessionAuthority("p", organism.id, id, node.generation, node.mode)
        }
        suspend fun child(id: String, parent: String = "root", tokens: Long = 1_000): String {
            store.command(scope(parent), id, OrganismCommand(OrganismAction.CREATE, name = id, tokens = tokens,
                task = SessionTask("Task", parent, "Checked", "source")))
            return "session-$id"
        }
    }

    @Test fun onlyCommonParentCanGrantAnAuditableRouteAcrossBranches() = runTest {
        val f = Fixture(); f.init()
        val a = f.child("a", tokens = 10_000); val b = f.child("b")
        val nested = f.child("nested", a)
        val grant = OrganismCommand(OrganismAction.ROUTE, target = b, source = nested, reason = "Share verified research")
        assertFailsWith<IllegalArgumentException> { f.store.command(f.scope(a), "bypass", grant) }
        f.store.command(f.scope(), "route", grant)
        val sent = f.store.command(f.scope(nested), "send", OrganismCommand(OrganismAction.SEND, target = b,
            packet = SessionContextPacket("Original context")))
        assertEquals(listOf(nested, a, "root", b), sent.outbox.single().route)
        assertEquals(nested, sent.outbox.single().sender)
        assertEquals("route", sent.outbox.single().routeGrantId)
        f.store.observe(sent.id, b, 1, SessionObservedState.STOPPED)
        f.store.command(f.scope(), "restore-b", OrganismCommand(OrganismAction.RESTORE, b, reason = "rechecked", tokens = 500))
        assertFailsWith<IllegalArgumentException> { f.store.command(f.scope(nested), "stale-route", OrganismCommand(OrganismAction.SEND,
            b, packet = SessionContextPacket("Must not reuse authority"))) }
    }

    @Test fun acceptedResultCannotBeRestoredAndCommitAloneDoesNotAcceptIt() = runTest {
        val f = Fixture(); f.init(); val child = f.child("a")
        val result = SessionResult("result", child, 1, "root", "Done", sourceVersion = "source", commitSha = "abc")
        f.store.recordResult(f.organism.id, result)
        val review = OrganismCommand(OrganismAction.REVIEW_RESULT, child, reason = "Evidence checked", resultId = result.id,
            accepted = true, sourceVersion = "source", checks = listOf("test result"))
        assertFalse(f.store.get(f.organism.id).results.single().accepted)
        assertFailsWith<IllegalArgumentException> { f.store.command(f.scope(), "early", review) }
        f.store.observe(f.organism.id, child, 1, SessionObservedState.COMPLETED)
        assertFailsWith<IllegalArgumentException> { f.store.command(f.scope(), "no-checks", review.copy(checks = emptyList())) }
        assertFailsWith<IllegalArgumentException> { f.store.command(f.scope(), "stale-source", review.copy(sourceVersion = "changed")) }
        val accepted = f.store.command(f.scope(), "accept", review)
        assertTrue(accepted.results.single().accepted)
        assertEquals("Evidence checked", accepted.reviews.single().evidence)
        assertEquals(accepted, f.store.command(f.scope(), "accept", review))
        assertFailsWith<IllegalArgumentException> { f.store.command(f.scope(), "restore", OrganismCommand(OrganismAction.RESTORE,
            child, reason = "already accepted", tokens = 500)) }
    }

    @Test fun immunityInspectsEvidenceOnceAndAComplaintAloneNeverQuarantines() = runTest {
        val f = Fixture(); f.init(); val child = f.child("a")
        f.store.command(f.scope(), "opinion", OrganismCommand(OrganismAction.SIGNAL, child, reason = "I disagree"))
        val noFault = f.store.inspectSignals(f.organism.id)
        assertEquals("NO_INTERVENTION", noFault.diagnoses.single().action)
        assertEquals(SessionDesiredState.RUN, noFault.sessions.getValue(child).desired)
        assertEquals(noFault, f.store.inspectSignals(noFault.id))
        f.store.observe(noFault.id, child, 1, SessionObservedState.UNKNOWN)
        f.store.command(f.scope(), "uncertain", OrganismCommand(OrganismAction.SIGNAL, child, reason = "Check pending operation"))
        val diagnosed = f.store.inspectSignals(noFault.id)
        assertEquals("QUARANTINE", diagnosed.diagnoses.last().action)
        assertTrue(diagnosed.diagnoses.last().evidence.single().contains("неизвестен"))
        assertEquals(SessionDesiredState.QUARANTINE, diagnosed.sessions.getValue(child).desired)
        assertEquals(0, diagnosed.sessions.getValue(child).retryCount)
        assertEquals(diagnosed, f.store.inspectSignals(noFault.id))
    }

    @Test fun userCanStopImmunityWhileDeterministicQuarantineStillWorks() = runTest {
        val f = Fixture(); f.init(); val child = f.child("a")
        f.store.requestUserStop(f.organism.id, f.organism.immunityId, "stop-immunity", false)
        f.store.command(f.scope(), "signal", OrganismCommand(OrganismAction.SIGNAL, child, reason = "Check"))
        assertTrue(f.store.inspectSignals(f.organism.id).diagnoses.isEmpty())
        val saved = f.store.quarantine(f.organism.id, child, 1, "unknown-effect", "External result unavailable")
        assertEquals(SessionDesiredState.QUARANTINE, saved.sessions.getValue(child).desired)
    }

    @Test fun oldArchiveReceiptCannotRearchiveAnExplicitlyRestoredSession() = runTest {
        val f = Fixture(); f.init(); val a = f.child("a"); val b = f.child("b")
        f.store.command(f.scope(), "archive", OrganismCommand(OrganismAction.ARCHIVE, a))
        f.store.observe(f.organism.id, a, 1, SessionObservedState.STOPPED)
        f.store.finishStop(f.organism.id, setOf(a))
        f.store.restoreByUser(f.organism.id, a, "restore", f.rules, "source")
        f.store.command(f.scope(), "stop-other", OrganismCommand(OrganismAction.STOP, b))
        f.store.observe(f.organism.id, b, 1, SessionObservedState.STOPPED)
        val saved = f.store.finishStop(f.organism.id, setOf(b))
        assertFalse(saved.sessions.getValue(a).archived)
    }

    @Test fun nextOrdinaryTurnPreservesUnprocessedContextAndRejectsOldAcknowledgement() = runTest {
        val f = Fixture(); f.init(); val child = f.child("a")
        f.store.recordResult(f.organism.id, SessionResult("result", child, 1, "root", "Result"))
        f.store.observe(f.organism.id, child, 1, SessionObservedState.COMPLETED)
        f.store.observe(f.organism.id, "root", 1, SessionObservedState.COMPLETED)
        val root = f.store.beginRun(f.organism.id, "root")
        assertEquals(2, root.generation)
        assertEquals(root.generation, f.store.get(f.organism.id).outbox.single().recipientGeneration)
        assertFailsWith<IllegalArgumentException> { f.store.acknowledge(f.organism.id, "result-result", "root", 1, false) }
        f.store.acknowledge(f.organism.id, "result-result", "root", root.generation, false)
    }

    @Test fun committedOrganismIsDiscoverableWithoutLegacyProjectionAndModeChangeRevokesOldAuthority() = runTest {
        val f = Fixture(); f.init()
        val old = f.scope()
        assertEquals(listOf(f.organism.id), SessionOrganismStore(f.storage).loadAll().map { it.id })
        assertFailsWith<IllegalArgumentException> { f.store.changeRootMode(f.organism.id, "root", CodingInteractionMode.CODE) }
        f.store.observe(f.organism.id, "root", old.generation, SessionObservedState.COMPLETED)
        val changed = f.store.changeRootMode(f.organism.id, "root", CodingInteractionMode.CODE)
        assertEquals(CodingInteractionMode.CODE, changed.sessions.getValue("root").mode)
        assertFailsWith<IllegalArgumentException> { f.store.check(old) }
        assertNull(changed.limits.tokens)
        assertEquals(f.organism.sessions.values.sumOf { it.remainingTokens }, changed.sessions.values.sumOf { it.remainingTokens })
    }

    @Test fun secretsAreMaskedInStoredTasksContextAndResultsWithoutCollapsingOperationIdentity() = runTest {
        val f = Fixture(); f.init()
        f.store.knownSecrets = { setOf("configured-secret-value") }
        val command = OrganismCommand(OrganismAction.CREATE, name = "Child", tokens = 1_000,
            task = SessionTask("Inspect configured-secret-value", "root", "password=guess", "source"))
        f.store.command(f.scope(), "secret-child", command)
        val child = "session-secret-child"
        val task = f.store.get(f.organism.id).sessions.getValue(child).task!!
        assertFalse(task.text.contains("configured-secret-value"))
        assertFalse(task.acceptance.contains("guess"))
        f.store.command(f.scope(), "secret-child", command)
        assertFailsWith<IllegalArgumentException> { f.store.command(f.scope(), "secret-child", command.copy(task = command.task!!.copy(text = "different secret"))) }
        f.store.command(f.scope(), "packet", OrganismCommand(OrganismAction.SEND, child,
            packet = SessionContextPacket("Context", attachments = listOf("https://host/?access_token=configured-secret-value"), omissions = "password=guess")))
        f.store.recordResult(f.organism.id, SessionResult("result", child, 1, "root", "configured-secret-value", artifacts = listOf("password=guess"), sourceVersion = "configured-secret-value"))
        val raw = f.storage.read("session-organism-${f.organism.id}")!!
        assertFalse(raw.contains("configured-secret-value"))
        assertFalse(raw.contains("password=guess"))
    }
}
