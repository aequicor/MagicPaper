package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.*
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ImmunityInterventionTest {
    private class Fixture {
        val storage = InMemoryKeyValueStore()
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val projects = JsonCodingProjectRepository(storage, json)
        val settings = JsonSettingsRepository(storage, json)
        var now = 1_000L
        var source = "source"
        val store = SessionOrganismStore(storage) { now }
        val service = SessionOrganismService(store, projects, settings) { source }
        val project = CodingProject("p", "Project", "/source", 1)
        var starts = 0
        var stops = 0
        lateinit var id: String
        suspend fun initialize() {
            projects.save(project)
            val root = CodingSession("root", "p", "Root", 1, researchMode = true, piSessionId = "old-native",
                planningRulesSnapshot = settings.load().planningRules.snapshot())
            projects.saveSession(root)
            id = service.ensure(root).id
            store.beginRun(id, root.id)
            service.project(store.get(id))
            service.canRecreateAfterQuarantine = { true }
            service.startChild = { child, _ -> starts++; store.beginRun(id, child.id); service.project(store.get(id)) }
            service.stopSubtree = { ids ->
                stops++
                val snapshot = store.get(id)
                ids.sortedBy { snapshot.subtree(it).size }.forEach { target ->
                    val node = store.get(id).sessions.getValue(target)
                    if (!node.settled) store.observe(id, target, node.generation, SessionObservedState.STOPPED)
                }
            }
        }
        suspend fun scope(owner: String = "root"): SessionAuthority {
            val node = store.get(id).sessions.getValue(owner)
            return SessionAuthority("p", id, owner, node.generation, node.mode)
        }
        suspend fun child(): String {
            store.command(scope(), "child", OrganismCommand(OrganismAction.CREATE, name = "Child", tokens = 1_000,
                task = SessionTask("Investigate", "root", "Verified findings", source)))
            service.project(store.get(id))
            return "session-child"
        }
        suspend fun signal(target: String = "root", signal: String = "signal"): ImmunityIntervention? {
            store.command(scope(), signal, OrganismCommand(OrganismAction.SIGNAL, target, reason = "Please inspect saved observations"))
            store.inspectSignals(id)
            val saved = store.proposeImmunityInterventions(id)
            service.project(saved)
            return saved.interventions.firstOrNull { it.signalId == signal }
        }
        suspend fun fail(target: String = "root") {
            val node = store.get(id).sessions.getValue(target)
            store.observe(id, target, node.generation, SessionObservedState.FAILED)
        }
    }

    @Test fun complaintAloneNeverCreatesActionProposalAndEvidenceSurvivesRestart() = runTest {
        val f = Fixture(); f.initialize()
        assertNull(f.signal(signal = "opinion"))
        f.fail()
        val proposal = assertNotNull(f.signal(signal = "failed"))
        assertTrue(proposal.evidence.single().contains("Runtime"))
        assertEquals(proposal.generation, f.store.get(f.id).diagnoses.last().generation)
        assertEquals(ImmunityInterventionState.PROPOSED, proposal.state)
        assertEquals(proposal, SessionOrganismStore(f.storage).get(f.id).interventions.single())
        assertEquals(1, f.store.proposeImmunityInterventions(f.id).interventions.size)
        assertEquals(0, f.starts)
    }

    @Test fun applicationEventCreatesAnEvidenceBoundProposalWithoutAnyModelLoop() = runTest {
        val f = Fixture(); f.initialize(); f.fail()
        f.store.command(f.scope(), "event", OrganismCommand(OrganismAction.SIGNAL, "root", reason = "Please inspect"))
        f.service.wakeImmunity(f.id)
        try {
            // Supervision runs on the real application dispatcher, so its deadline must not
            // jump ahead on runTest's virtual clock while that dispatcher is still working.
            val saved = withContext(Dispatchers.Default) { withTimeout(5_000) {
                f.store.organisms.first { it[f.id]?.interventions?.isNotEmpty() == true }.getValue(f.id)
            } }
            assertEquals("event", saved.interventions.single().signalId)
            assertEquals(1, f.stops)
            assertEquals(0, f.starts)
            assertEquals(ImmunityInterventionState.PROPOSED, saved.interventions.single().state)
        } finally { f.service.shutdown() }
    }

    @Test fun allReversibleActionsAreInvocableAndRepeatedConfirmationOnlyReadsProof() = runTest {
        for (action in listOf(ImmunityAction.PAUSE, ImmunityAction.QUARANTINE, ImmunityAction.STOP, ImmunityAction.ARCHIVE)) {
            val f = Fixture(); f.initialize(); f.fail()
            val proposal = assertNotNull(f.signal())
            f.service.approveImmunityIntervention(f.id, proposal.id, action)
            val saved = f.store.get(f.id)
            val node = saved.sessions.getValue("root")
            assertEquals(if (action == ImmunityAction.PAUSE) SessionDesiredState.PAUSE else if (action == ImmunityAction.QUARANTINE) SessionDesiredState.QUARANTINE else SessionDesiredState.STOP, node.desired)
            assertEquals(action == ImmunityAction.ARCHIVE, node.archived)
            assertEquals(ImmunityInterventionState.COMPLETED, saved.interventions.single().state)
            assertEquals(saved.immunityId, saved.audit.first { it.action == "IMMUNITY_${action.name}" }.actor)
            f.service.approveImmunityIntervention(f.id, proposal.id, action)
            assertEquals(1, f.stops)
            assertEquals(saved, f.store.get(f.id))
        }
    }

    @Test fun recreateZygoteRevokesOldNativeContextAndAwaitsANewHumanTask() = runTest {
        val f = Fixture(); f.initialize(); f.fail()
        val proposal = assertNotNull(f.signal())
        val before = f.store.get(f.id)
        f.service.approveImmunityIntervention(f.id, proposal.id, ImmunityAction.RECREATE)
        val saved = f.store.get(f.id); val node = saved.sessions.getValue("root")
        assertEquals(proposal.generation + 1, node.generation)
        assertEquals(proposal.generation, node.previousGeneration)
        assertEquals(SessionObservedState.PENDING, node.observed)
        assertEquals(SessionDesiredState.RUN, node.desired)
        assertEquals(0, f.starts)
        assertEquals("", f.projects.sessions("p").single { it.id == "root" }.piSessionId)
        assertEquals(before.sessions.values.sumOf { it.remainingTokens }, saved.sessions.values.sumOf { it.remainingTokens })
        assertTrue(saved.sessions.getValue(saved.immunityId).remainingTokens > 0)
        assertFailsWith<ToolArgumentRejection> { f.store.command(SessionAuthority("p", f.id, "root", proposal.generation, node.mode), "late", OrganismCommand(OrganismAction.SIGNAL, "root", reason = "Old runtime")) }
        f.service.approveImmunityIntervention(f.id, proposal.id, ImmunityAction.RECREATE)
        assertEquals(saved, f.store.get(f.id))
    }

    @Test fun childRecreationChecksSourcesRulesAndUnresolvedEffectsBeforeStarting() = runTest {
        val f = Fixture(); f.initialize(); val child = f.child(); f.fail(child)
        val proposal = assertNotNull(f.signal(child))
        f.source = "changed"
        assertFailsWith<IllegalArgumentException> { f.service.approveImmunityIntervention(f.id, proposal.id, ImmunityAction.RECREATE) }
        f.source = "source"
        assertFailsWith<IllegalArgumentException> { f.store.acceptImmunityIntervention(f.id, proposal.id, ImmunityAction.RECREATE,
            f.settings.load().planningRules.snapshot().copy(version = "changed"), "source", reconciled = true) }
        f.service.canRecreateAfterQuarantine = { false }
        assertFailsWith<IllegalArgumentException> { f.service.approveImmunityIntervention(f.id, proposal.id, ImmunityAction.RECREATE) }
        assertEquals(ImmunityInterventionState.PROPOSED, f.store.get(f.id).interventions.single().state)
        assertEquals(0, f.starts)
        f.service.canRecreateAfterQuarantine = { true }
        f.service.approveImmunityIntervention(f.id, proposal.id, ImmunityAction.RECREATE)
        assertEquals(1, f.starts)
        assertEquals(proposal.generation + 1, f.store.get(f.id).sessions.getValue(child).lastStartedGeneration)
        assertEquals(ImmunityInterventionState.COMPLETED, f.store.get(f.id).interventions.single().state)
    }

    @Test fun stoppedImmunityStaleGenerationAndRejectedProposalCannotGrantAuthority() = runTest {
        val f = Fixture(); f.initialize(); f.fail()
        val proposal = assertNotNull(f.signal())
        f.store.requestUserStop(f.id, f.store.get(f.id).immunityId, "stop-immunity", false)
        assertFailsWith<IllegalArgumentException> { f.service.approveImmunityIntervention(f.id, proposal.id, ImmunityAction.RECREATE) }
        f.service.dismissImmunityIntervention(f.id, proposal.id)
        assertFailsWith<IllegalArgumentException> { f.service.approveImmunityIntervention(f.id, proposal.id, ImmunityAction.RECREATE) }
        assertEquals(1, f.store.get(f.id).sessions.getValue("root").generation)
        val g = Fixture(); g.initialize(); g.fail()
        val stale = assertNotNull(g.signal())
        // Another explicitly accepted recovery consumes this generation.
        g.store.command(g.scope(), "second-signal", OrganismCommand(OrganismAction.SIGNAL, "root", reason = "Another diagnostic"))
        g.store.inspectSignals(g.id); g.store.proposeImmunityInterventions(g.id)
        val newer = g.store.get(g.id).interventions.last()
        g.service.approveImmunityIntervention(g.id, newer.id, ImmunityAction.RECREATE)
        assertFailsWith<IllegalArgumentException> { g.service.approveImmunityIntervention(g.id, stale.id, ImmunityAction.RECREATE) }
    }

    @Test fun recoveryHasCooldownAndBoundedRetryBudget() = runTest {
        val f = Fixture(); f.initialize(); f.fail()
        val first = assertNotNull(f.signal())
        f.service.approveImmunityIntervention(f.id, first.id, ImmunityAction.RECREATE)
        f.fail(); val second = assertNotNull(f.signal(signal = "second"))
        assertFailsWith<IllegalArgumentException> { f.service.approveImmunityIntervention(f.id, second.id, ImmunityAction.RECREATE) }
        f.now += 60_000
        f.service.approveImmunityIntervention(f.id, second.id, ImmunityAction.RECREATE)
        f.fail(); f.now += 60_000
        f.service.approveImmunityIntervention(f.id, assertNotNull(f.signal(signal = "third")).id, ImmunityAction.RECREATE)
        f.fail(); f.now += 60_000
        val exhausted = assertNotNull(f.signal(signal = "exhausted"))
        assertFalse(ImmunityAction.RECREATE in exhausted.actions)
        assertFailsWith<IllegalArgumentException> { f.service.approveImmunityIntervention(f.id, exhausted.id, ImmunityAction.RECREATE) }
        assertEquals(3, f.store.get(f.id).sessions.getValue("root").retryCount)
    }

    @Test fun deletionUsesConfirmedProposalIdentityAndRetainsAuditAfterTombstone() = runTest {
        val f = Fixture(); f.initialize(); f.fail(); val proposal = assertNotNull(f.signal())
        var deletes = 0
        f.service.approveImmunityIntervention(f.id, proposal.id, ImmunityAction.DELETE_HISTORY) { project, target ->
            deletes++
            val accepted = f.store.get(f.id).interventions.single()
            assertEquals(ImmunityInterventionState.ACCEPTED, accepted.state)
            assertEquals(ImmunityAction.DELETE_HISTORY, accepted.action)
            assertEquals(f.store.get(f.id).sessions.keys, accepted.affected)
            assertNotNull(accepted.confirmedAt)
            assertEquals("p", project)
            // A generic stop checkpoint is not proof that history deletion happened.
            f.service.stopSubtree(accepted.affected)
            f.store.finishStop(f.id, accepted.affected)
            assertEquals(SessionOperationState.ACCEPTED, f.store.get(f.id).operations.getValue("${proposal.id}-DELETE_HISTORY").state)
            f.service.deleteHistory(project, target)
        }
        val deleted = f.store.get(f.id)
        assertNotNull(deleted.deletedAt)
        assertEquals(deleted.sessions.keys, deleted.historyDeletedIds)
        assertEquals(ImmunityInterventionState.COMPLETED, deleted.interventions.single().state)
        assertTrue(deleted.audit.any { it.action == "IMMUNITY_DELETE_HISTORY" })
        assertTrue(deleted.audit.any { it.action == "DELETE_HISTORY" })
        f.service.approveImmunityIntervention(f.id, proposal.id, ImmunityAction.DELETE_HISTORY) { _, _ -> deletes++ }
        assertEquals(1, deletes)
    }

    @Test fun lostExternalCompletionNeverRepeatsARecoveryLaunch() = runTest {
        val f = Fixture(); f.initialize(); val child = f.child(); f.fail(child)
        val proposal = assertNotNull(f.signal(child))
        f.service.startChild = { session, _ -> f.starts++; f.store.beginRun(f.id, session.id); error("lost launch response") }
        assertFailsWith<IllegalStateException> { f.service.approveImmunityIntervention(f.id, proposal.id, ImmunityAction.RECREATE) }
        assertEquals(ImmunityInterventionState.UNKNOWN, f.store.get(f.id).interventions.single().state)
        f.service.approveImmunityIntervention(f.id, proposal.id, ImmunityAction.RECREATE)
        assertEquals(1, f.starts)
        assertEquals(ImmunityInterventionState.COMPLETED, f.store.get(f.id).interventions.single().state)
    }

    @Test fun unknownDeleteCannotRunAgainUntilSavedTombstoneProvesItsOutcome() = runTest {
        val f = Fixture(); f.initialize(); f.fail(); val proposal = assertNotNull(f.signal())
        var deletes = 0
        assertFailsWith<IllegalStateException> {
            f.service.approveImmunityIntervention(f.id, proposal.id, ImmunityAction.DELETE_HISTORY) { _, _ -> deletes++; error("native stop unknown") }
        }
        assertNull(f.store.get(f.id).deletedAt)
        assertEquals(ImmunityInterventionState.UNKNOWN, f.store.get(f.id).interventions.single().state)
        assertFailsWith<IllegalArgumentException> {
            f.service.approveImmunityIntervention(f.id, proposal.id, ImmunityAction.DELETE_HISTORY) { _, _ -> deletes++ }
        }
        assertEquals(1, deletes)
        assertTrue(f.projects.sessions("p").isNotEmpty())
    }

    @Test fun ownerReceiptInspectionIncludesEveryRequestAndNativeBucket() = runTest {
        val store = StoredToolReceipts(InMemoryKeyValueStore())
        val args = buildJsonObject { put("command", "safe") }
        store.save(ToolReceipt("p/owner/first/tool", "native", args, ToolPhase.SUCCEEDED))
        store.save(ToolReceipt("p/owner/second/native/id", "native", args, ToolPhase.UNKNOWN, native = true))
        store.save(ToolReceipt("p/other/second/native/id", "native", args, ToolPhase.UNKNOWN, native = true))
        val history = store.forOwner("p", "owner")
        assertEquals(2, history.size)
        assertEquals(1, history.count { it.phase == ToolPhase.UNKNOWN })
    }

    @Test fun aggregateUnknownIntegrationBlocksRecoveryEvenWithoutAToolReceipt() = runTest {
        val f = Fixture(); f.initialize(); f.fail(); val proposal = assertNotNull(f.signal())
        val saved = f.store.get(f.id)
        val pending = SessionIntegration(SessionIntegrationRequest("pending", f.id, "root", proposal.generation,
            listOf("result"), listOf(listOf("check")), f.project.path, "source"), SessionIntegrationPhase.UNKNOWN)
        // Crash fixture: the aggregate intent survives even when the tool receipt was never saved.
        f.storage.write("session-organism-${f.id}", f.json.encodeToString(SessionOrganism.serializer(), saved.copy(integrations = mapOf("pending" to pending))))
        assertFailsWith<IllegalArgumentException> { f.service.approveImmunityIntervention(f.id, proposal.id, ImmunityAction.RECREATE) }
        assertEquals(proposal.generation, f.store.get(f.id).sessions.getValue("root").generation)
        assertEquals(ImmunityInterventionState.PROPOSED, f.store.get(f.id).interventions.single().state)
    }
}
