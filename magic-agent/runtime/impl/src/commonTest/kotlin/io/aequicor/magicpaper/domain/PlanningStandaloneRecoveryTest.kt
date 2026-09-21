package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.*
import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.storage.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlanningStandaloneRecoveryTest {
    private class Fixture {
        val events = InMemoryEventJournal()
        val kv = InMemoryKeyValueStore()
        val store = TestPlanningStore(JsonPlanningRepository(kv, Json), events)
        val projects = journalCodingProjects(kv, Json)
        val reconciled = mutableListOf<String>()
        var failSession: String? = null
        var cancelled = false
        val recovery = PlanningJournalRecovery(store, object : CodingRuntime by NoopCodingRuntime {
            override suspend fun reconcile(sessionId: String) {
                if (cancelled) throw CancellationException("cancelled")
                check(sessionId != failSession) { "Unproven termination" }
                reconciled += sessionId
            }
        }, projects)
        suspend fun seed() {
            projects.createTestProject(CodingProject("project", "Project", "/fixture", 1))
            val milestones = listOf("one", "two").map { id ->
                val session = CodingSession("worker-$id", "project", "Worker $id", 1, planId = "plan", stageId = id)
                projects.createTestSession(session)
                Milestone(id, id, description = "Task $id", attempts = listOf(StageAttempt("attempt-$id", session.id,
                    StageAssignment("profile", "model"), phase = AttemptPhase.EXECUTING)))
            }
            store.save(Plan("plan", "project", "Goal", milestones = milestones, confirmedRevision = 1))
            store.command("plan", PlanningMachine.Intent.Start("run", PlanningRulesSettings().snapshot(), PlanningMachine.Stamp("start", 1)))
            val ref = checkNotNull(store.currentAdmission("plan"))
            val intents = milestones.map { stage -> store.beginIntent("plan", PlanJournalOperation.AGENT_INTENT,
                stage.id, stage.attempts.single().id, ref) }
            intents.forEach { store.markIntentUnknown(it) }
            assertNull(store.planFor("plan")!!.issue, "The standalone path must not rely on a published issue")
        }
    }

    @Test fun oneInspectionConfirmsAllOwnersWithoutAdmissionAndCannotBeReused() = runTest {
        val f = Fixture(); f.seed()
        val shown = f.recovery.inspectPlan("plan")
        assertEquals(2, shown.operations.size)
        assertEquals(2, f.store.unsettled("plan").size)
        assertTrue(f.reconciled.isEmpty(), "Inspection does not invoke the runtime")
        f.recovery.confirmPlan(shown)
        assertEquals(setOf("worker-one", "worker-one-merge", "worker-one-delivery",
            "worker-two", "worker-two-merge", "worker-two-delivery"), f.reconciled.toSet())
        assertTrue(f.store.unsettled("plan").isEmpty())
        assertNull(f.store.currentAdmission("plan"), "Confirmation never launches execution")
        assertEquals(PlanningMachine.RunPhase.INTERRUPTED, f.store.machineStates.value["plan"]?.run?.phase)
        assertFailsWith<IllegalArgumentException> { f.recovery.confirmPlan(shown) }
    }

    @Test fun changedPlanOrSessionInvalidatesConfirmationBeforeNativeCleanup() = runTest {
        for (changeSession in listOf(false, true)) {
            val f = Fixture(); f.seed()
            val shown = f.recovery.inspectPlan("plan")
            if (changeSession) {
                val session = f.projects.sessions("project").first()
                f.projects.dispatch("project", CodingMachine.Intent.RenameSession(CodingMachine.ref(session), "Changed"))
            } else f.store.command("plan", PlanningMachine.Fact.IssueObserved(null,
                PlanningIssue(IssueKind.CONFIGURATION, "Changed", requiresUser = true), stamp = PlanningMachine.Stamp("changed", 2)))
            assertFailsWith<IllegalArgumentException> { f.recovery.confirmPlan(shown) }
            assertTrue(f.reconciled.isEmpty())
            assertEquals(2, f.store.unsettled("plan").size)
        }
    }

    @Test fun failedSecondTerminationKeepsEveryUnknownOperationAndConsumesTheInspection() = runTest {
        val f = Fixture(); f.seed()
        val shown = f.recovery.inspectPlan("plan")
        f.failSession = "worker-two"
        assertFailsWith<IllegalStateException> { f.recovery.confirmPlan(shown) }
        assertEquals(2, f.store.unsettled("plan").size)
        assertFailsWith<IllegalArgumentException> { f.recovery.confirmPlan(shown) }
        f.failSession = null
        f.recovery.confirmPlan(f.recovery.inspectPlan("plan"))
        assertTrue(f.store.unsettled("plan").isEmpty())
    }

    @Test fun cancellationDoesNotAcknowledgeUnknownOperations() = runTest {
        val f = Fixture(); f.seed()
        val shown = f.recovery.inspectPlan("plan")
        f.cancelled = true
        assertFailsWith<CancellationException> { f.recovery.confirmPlan(shown) }
        assertEquals(2, f.store.unsettled("plan").size)
        assertNull(f.store.currentAdmission("plan"))
    }

    @Test fun userJournalConfirmationPreservesAnIndependentNativeIssue() = runTest {
        val f = Fixture(); f.seed()
        val issue = PlanningIssue(IssueKind.UNCERTAIN, "An unrelated native command is still unconfirmed", requiresUser = true)
        f.store.command("plan", PlanningMachine.Fact.IssueObserved(null, issue, stamp = PlanningMachine.Stamp("issue", 2)))
        f.recovery.confirmPlan(f.recovery.inspectPlan("plan"))
        assertTrue(f.store.unsettled("plan").isEmpty())
        assertEquals(issue, f.store.planFor("plan")!!.issue)
        assertNull(f.store.currentAdmission("plan"))
    }

    @Test fun resetOrMissingOwnedSessionCannotBeConfirmed() = runTest {
        val f = Fixture(); f.seed()
        val shown = f.recovery.inspectPlan("plan")
        f.events.drop("plan")
        assertFailsWith<IllegalStateException> { f.recovery.confirmPlan(shown) }
        assertTrue(f.reconciled.isEmpty())
        val orphan = Fixture(); orphan.seed()
        val inaccessible = PlanningJournalRecovery(orphan.store, NoopCodingRuntime, null)
        assertFailsWith<IllegalArgumentException> { inaccessible.inspectPlan("plan") }
    }

    @Test fun prepareFailureBeforeAnySessionCanBeInspectedWithoutInventingANativeOwner() = runTest {
        val kv = InMemoryKeyValueStore()
        val store = TestPlanningStore(JsonPlanningRepository(kv, Json))
        val projects = journalCodingProjects(kv, Json)
        val project = CodingProject("project", "Project", "/fixture", 1)
        projects.createTestProject(project)
        val profile = LlmProfile("profile", "Model", baseUrl = "http://fixture/v1", modelId = "model",
            favoriteModels = listOf("model"), modelLibraryVersion = 1)
        val profiles = JsonLlmProfileRepository(kv, Json).also { it.save(profile) }
        val settings = JsonSettingsRepository(kv, Json).also { it.save(AppSettings(activeLlmProfileId = profile.id)) }
        var prepared = 0
        var reconciled = 0
        val runtime = object : CodingRuntime by NoopCodingRuntime {
            override val supported = true
            override suspend fun reconcile(sessionId: String) { reconciled++; error("No native session was created") }
        }
        val workspace = object : PlanningWorkspace by LocalPlanningWorkspace() {
            override suspend fun prepare(project: CodingProject, runId: String, operation: WorkspaceOperation): PlanWorkspace {
                prepared++
                error("Preparation interrupted after a possible workspace change")
            }
        }
        val ports = TestPlanningExecutionPorts()
        val execution = PlanningExecutionService(store, runtime, projects, profiles, settings,
            object : MilestoneVerifier {
                override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) =
                    error("No model should run")
            }, workspaces = workspace, scope = backgroundScope, attemptAuthority = ports, chatHooksProvider = { null })
        store.save(Plan("plan", project.id, "Goal", confirmedRevision = 1,
            milestones = listOf(Milestone("stage", "Stage", description = "Task", agentProfileId = profile.id))))
        execution.start(project.id); runCurrent()
        assertEquals(1, prepared)
        assertTrue(projects.sessions(project.id).isEmpty())
        assertTrue(store.planFor("plan")!!.milestones.single().attempts.isEmpty())
        assertEquals(listOf(PlanJournalOperation.PREPARE_INTENT.wire), store.unsettled("plan").map { it.operation })
        val shown = execution.inspectPlanRecovery("plan")
        assertEquals(1, prepared)
        execution.confirmPlanRecovery(shown)
        assertEquals(0, reconciled)
        assertEquals(1, prepared)
        assertNull(store.currentAdmission("plan"))
        assertTrue(store.unsettled("plan").isEmpty())
        execution.start(project.id); runCurrent()
        assertEquals(2, prepared, "Only a fresh explicit command can repeat preparation")
        val withoutSessions = PlanningJournalRecovery(store, runtime, null)
        withoutSessions.confirmPlan(withoutSessions.inspectPlan("plan"))
        assertEquals(0, reconciled)
        assertEquals(2, prepared)
        execution.shutdown()
    }

    @Test fun finalAuxiliaryAttemptUsesItsSavedIdentityWithoutCreatingAProjectSession() = runTest {
        for (operation in listOf(PlanJournalOperation.FINAL_VERIFICATION_INTENT, PlanJournalOperation.DELIVERY_CONFLICT_INTENT)) {
            val kv = InMemoryKeyValueStore()
            val store = TestPlanningStore(JsonPlanningRepository(kv, Json))
            val projects = journalCodingProjects(kv, Json)
            projects.createTestProject(CodingProject("project", "Project", "/fixture", 1))
            val final = StageAttempt("final-attempt", "final-session", StageAssignment("profile", "model"),
                phase = AttemptPhase.EXECUTING)
            store.save(Plan("plan", "project", "Goal", confirmedRevision = 1,
                milestones = listOf(Milestone("stage", "Stage", description = "Task")), finalAttempt = final))
            store.command("plan", PlanningMachine.Intent.Start("run", PlanningRulesSettings().snapshot(), PlanningMachine.Stamp("start", 1)))
            val intent = store.beginIntent("plan", operation, "", final.id, checkNotNull(store.currentAdmission("plan")))
            store.markIntentUnknown(intent)
            val reconciled = mutableListOf<String>()
            val recovery = PlanningJournalRecovery(store, object : CodingRuntime by NoopCodingRuntime {
                override suspend fun reconcile(sessionId: String) { reconciled += sessionId }
            }, projects)
            val shown = recovery.inspectPlan("plan")
            assertTrue(reconciled.isEmpty())
            recovery.confirmPlan(shown)
            assertEquals(setOf(final.sessionId, "${final.sessionId}-merge", "${final.sessionId}-delivery"), reconciled.toSet())
            assertTrue(projects.sessions("project").isEmpty(), "No auxiliary session row may be invented for recovery")
            assertEquals(final, store.planFor("plan")!!.finalAttempt)
            assertTrue(store.unsettled("plan").isEmpty())
            assertNull(store.currentAdmission("plan"))
        }
    }
}
