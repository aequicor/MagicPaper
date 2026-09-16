package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.data.planning.JsonPlanningRepository
import io.aequicor.magicpaper.data.planning.PlanningStore
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.data.storage.InMemorySecretStore
import io.aequicor.magicpaper.data.storage.JsonLlmProfileRepository
import io.aequicor.magicpaper.data.storage.JsonSettingsRepository
import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.data.storage.StorageException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OrchestrationInputAcceptanceTest {
    private val parent = CodingSession("parent", "project", "Original name", 1,
        planningMode = true, role = CodingSessionRole.ORCHESTRATOR, engine = CodingEngine.PI)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private inner class Fixture(scope: TestScope, val kv: KeyValueStore = InMemoryKeyValueStore()) {
        val backing = JsonCodingProjectRepository(kv, json)
        var failProjection = false
        var projectionFailures = 0
        var beforeSessionRead: (suspend () -> Unit)? = null
        val projects = object : CodingProjectRepository by backing {
            override suspend fun sessions(projectId: String): List<CodingSession> {
                val beforeRead = beforeSessionRead
                beforeSessionRead = null
                beforeRead?.invoke()
                return backing.sessions(projectId)
            }
            override suspend fun saveMessages(projectId: String, sessionId: String, messages: List<CodingMessage>): List<CodingMessage> {
                if (failProjection) {
                    projectionFailures++
                    throw StorageException("injected history projection", StorageException.Kind.WRITE)
                }
                return backing.saveMessages(projectId, sessionId, messages)
            }
        }
        val store = PlanningStore(JsonPlanningRepository(kv, json))
        private val profiles = JsonLlmProfileRepository(kv, json, InMemorySecretStore())
        private val settings = JsonSettingsRepository(kv, json, InMemorySecretStore())
        var modelCalls = 0
        private val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
                modelCalls++
                awaitCancellation()
            }
        }
        private val execution = PlanningExecutionService(store, NoopCodingRuntime, projects, profiles, settings,
            object : MilestoneVerifier {
                override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?): Verdict =
                    error("No native execution expected")
            }, scope = scope.backgroundScope)
        val service = OrchestrationService(store, execution, projects, profiles, settings, PlanComposer(gateway), gateway,
            scope.backgroundScope, workerDispatcher = StandardTestDispatcher(scope.testScheduler))

        suspend fun initialize() {
            backing.save(CodingProject(parent.projectId, "Test project", "/fixture", 1))
            backing.saveSession(parent)
            profiles.save(LlmProfile("model", "Test model", baseUrl = "http://fixture/v1", modelId = "m"))
            settings.save(AppSettings(activeLlmProfileId = "model"))
        }
        suspend fun close() {
            service.shutdown()
            execution.shutdown()
        }
    }

    @Test fun acceptedInputSurvivesProjectionFailureAndRetryAfterRestartDoesNotDuplicateIt() = runTest {
        val first = Fixture(this)
        first.initialize()
        first.failProjection = true
        var accepted = 0
        checkNotNull(first.service.send(parent, "Edit the scheduled message", inputId = "form-operation", onAccepted = {
            val durable = JsonCodingProjectRepository(first.kv, json).orchestration(parent.id)
            assertEquals("form-operation", durable?.inputs?.single()?.id)
            assertEquals("Edit the scheduled message", durable?.inputs?.single()?.text)
            accepted++
        })).join()
        assertEquals(1, accepted, "History projection must not hide a durable acceptance")
        assertTrue(first.projectionFailures > 0)
        assertNotNull(first.service.error.value)
        assertEquals(0, first.modelCalls)
        first.close()

        val reopened = Fixture(this, first.kv)
        checkNotNull(reopened.service.send(parent, "Edit the scheduled message", inputId = "form-operation", onAccepted = { accepted++ })).join()
        assertEquals(2, accepted, "Retry acknowledges the existing input")
        val inputs = JsonCodingProjectRepository(first.kv, json).orchestration(parent.id)?.inputs.orEmpty()
        assertEquals(listOf("form-operation"), inputs.map { it.id })
        assertEquals("Edit the scheduled message", inputs.single().text)
        reopened.close()
    }

    @Test fun reusedInputIdentityWithDifferentPayloadIsRejectedBeforeChangingSessionOrQueue() = runTest {
        val fixture = Fixture(this)
        fixture.initialize()
        val original = OrchestrationState(parent.id, parent.projectId,
            inputs = listOf(OrchestrationInput("accepted-id", "Original accepted request", 1)))
        fixture.backing.saveOrchestration(original)
        var accepted = false
        checkNotNull(fixture.service.send(parent, "Different request", inputId = "accepted-id", onAccepted = { accepted = true })).join()
        assertFalse(accepted)
        assertNotNull(fixture.service.error.value)
        assertEquals(original, JsonCodingProjectRepository(fixture.kv, json).orchestration(parent.id))
        assertEquals(parent.name, fixture.backing.sessions(parent.projectId).single { it.id == parent.id }.name)
        assertEquals(0, fixture.modelCalls)
        fixture.close()
    }

    @Test fun workerDeliveryIsAcceptedBeforeProjectionAndSameIdentityNeverEnqueuesTwice() = runTest {
        val first = Fixture(this)
        first.initialize()
        val plan = Plan("plan", parent.projectId, "Goal", parentSessionId = parent.id,
            runId = "run", milestones = listOf(Milestone("stage", "Stage")), intent = ExecutionIntent.STOP)
        first.store.save(plan)
        val worker = CodingSession("plan-plan-stage-stage", parent.projectId, "Worker", 1,
            planId = plan.id, stageId = "stage", parentSessionId = parent.id,
            role = CodingSessionRole.WORKER, engine = CodingEngine.PI)
        first.backing.saveSession(worker)
        first.failProjection = true
        var accepted = 0
        checkNotNull(first.service.send(worker, "Worker instruction", inputId = "delivery-operation", onAccepted = {
            val durable = JsonPlanningRepository(first.kv, json).plans().single { it.id == plan.id }
            assertEquals("delivery-operation", durable.deliveries.single().id)
            accepted++
        })).join()
        assertEquals(1, accepted)
        assertTrue(first.projectionFailures > 0)
        assertNotNull(first.service.error.value)
        first.close()

        val reopened = Fixture(this, first.kv)
        checkNotNull(reopened.service.send(worker, "Worker instruction", inputId = "delivery-operation", onAccepted = { accepted++ })).join()
        assertEquals(2, accepted)
        val deliveries = JsonPlanningRepository(first.kv, json).plans().single { it.id == plan.id }.deliveries
        assertEquals(listOf("delivery-operation"), deliveries.map { it.id })
        assertEquals("Worker instruction", deliveries.single().text)
        assertEquals(0, reopened.modelCalls)
        reopened.close()
    }

    @Test fun retryOfAcceptedWorkerInputDoesNotBecomeANewParentInputAfterPlanCompletes() = runTest {
        val first = Fixture(this)
        first.initialize()
        val plan = Plan("plan", parent.projectId, "Goal", parentSessionId = parent.id,
            runId = "run", milestones = listOf(Milestone("stage", "Stage")), intent = ExecutionIntent.STOP)
        first.store.save(plan)
        val worker = CodingSession("plan-plan-stage-stage", parent.projectId, "Worker", 1,
            planId = plan.id, stageId = "stage", parentSessionId = parent.id,
            role = CodingSessionRole.WORKER, engine = CodingEngine.PI)
        first.backing.saveSession(worker)
        var accepted = 0
        checkNotNull(first.service.send(worker, "Accepted before completion", inputId = "stable-worker-operation",
            onAccepted = { accepted++ })).join()
        assertEquals(1, accepted)
        first.store.update(plan.id) { it.copy(phase = ExecutionPhase.COMPLETE) }
        first.close()

        val reopened = Fixture(this, first.kv)
        checkNotNull(reopened.service.send(worker, "Accepted before completion", inputId = "stable-worker-operation",
            onAccepted = { accepted++ })).join()
        assertEquals(2, accepted)
        assertTrue(JsonCodingProjectRepository(first.kv, json).orchestration(parent.id)?.inputs.orEmpty().isEmpty(),
            "A retry must acknowledge its original durable delivery rather than choose a new destination")
        val deliveries = JsonPlanningRepository(first.kv, json).plans().single { it.id == plan.id }.deliveries
        assertEquals(listOf("stable-worker-operation"), deliveries.map { it.id })
        assertEquals(0, reopened.modelCalls)
        reopened.close()
    }

    @Test fun forwardedReceiptSurvivesWorkerRenameAndPlanResumptionWithoutChangingDestination() = runTest {
        val first = Fixture(this)
        first.initialize()
        val plan = Plan("plan", parent.projectId, "Goal", parentSessionId = parent.id, runId = "run",
            milestones = listOf(Milestone("stage", "Stage")), phase = ExecutionPhase.COMPLETE)
        first.store.save(plan)
        val worker = CodingSession("plan-plan-stage-stage", parent.projectId, "Original worker name", 1,
            planId = plan.id, stageId = "stage", parentSessionId = parent.id,
            role = CodingSessionRole.WORKER, engine = CodingEngine.PI)
        first.backing.saveSession(worker)
        first.failProjection = true
        var accepted = 0
        checkNotNull(first.service.send(worker, "Forwarded request", inputId = "forwarded-operation", onAccepted = { accepted++ })).join()
        assertEquals(1, accepted)
        val committed = checkNotNull(JsonCodingProjectRepository(first.kv, json).orchestration(parent.id)).inputs.single()
        assertEquals(worker.id, committed.sourceSessionId)
        assertEquals("Forwarded request", committed.sourceText)
        first.store.update(plan.id) { it.copy(phase = ExecutionPhase.EXECUTING) }
        val renamed = worker.copy(name = "Renamed after acceptance")
        first.backing.saveSession(renamed)
        first.close()

        val reopened = Fixture(this, first.kv)
        checkNotNull(reopened.service.send(renamed, "Forwarded request", inputId = "forwarded-operation", onAccepted = { accepted++ })).join()
        assertEquals(2, accepted)
        val inputs = checkNotNull(JsonCodingProjectRepository(first.kv, json).orchestration(parent.id)).inputs
        assertEquals(listOf("forwarded-operation"), inputs.map { it.id })
        assertEquals(committed.text, inputs.single().text)
        assertTrue(JsonPlanningRepository(first.kv, json).plans().single { it.id == plan.id }.deliveries.isEmpty())
        checkNotNull(reopened.service.send(renamed, "Different request", inputId = "forwarded-operation", onAccepted = { accepted++ })).join()
        assertEquals(2, accepted, "A stable origin must still reject a changed payload")
        assertNotNull(reopened.service.error.value)
        reopened.close()
    }

    @Test fun legacyForwardedReceiptWithoutOriginMetadataIsAcknowledgedOnlyForItsExactText() = runTest {
        val fixture = Fixture(this)
        fixture.initialize()
        val plan = Plan("plan", parent.projectId, "Goal", parentSessionId = parent.id,
            milestones = listOf(Milestone("stage", "Stage")))
        fixture.store.save(plan)
        val worker = CodingSession("plan-plan-stage-stage", parent.projectId, "Worker", 1,
            planId = plan.id, stageId = "stage", parentSessionId = parent.id,
            role = CodingSessionRole.WORKER, engine = CodingEngine.PI)
        fixture.backing.saveSession(worker)
        val legacy = OrchestrationInput("legacy-forwarded", "По этапу «${worker.name}» (${worker.subtitle()}): Request", 1)
        fixture.backing.saveOrchestration(OrchestrationState(parent.id, parent.projectId, inputs = listOf(legacy)))
        var accepted = 0
        checkNotNull(fixture.service.send(worker, "Request", inputId = legacy.id, onAccepted = { accepted++ })).join()
        assertEquals(1, accepted)
        assertEquals(listOf(legacy.id), fixture.backing.orchestration(parent.id)?.inputs?.map { it.id })
        assertTrue(fixture.store.planFor(plan.id)!!.deliveries.isEmpty())
        checkNotNull(fixture.service.send(worker, "Different request", inputId = legacy.id, onAccepted = { accepted++ })).join()
        assertEquals(1, accepted)
        assertNotNull(fixture.service.error.value)
        fixture.close()
    }

    @Test fun restoredRenameCannotOverwriteANameChangedAfterItsBaseline() = runTest {
        val fixture = Fixture(this)
        fixture.initialize()
        val form = fixture.service.forms.rename(parent)
        form.draft.awaitSaved()
        form.update("Requested name")
        form.draft.awaitSaved()
        fixture.backing.saveSession(parent.copy(name = "Another accepted name", nameManuallySet = true))
        fixture.service.saveRename(parent, form)
        runCurrent()
        assertEquals("Another accepted name", fixture.backing.sessions(parent.projectId).single { it.id == parent.id }.name)
        assertEquals("Requested name", form.draft.state.value.value.text)
        assertNotNull(form.state.value.error)
        fixture.close()
    }

    @Test fun renameAndDeletionAreSerializedSoASuspendedRenameCannotRecreateDeletedSession() = runTest {
        val fixture = Fixture(this)
        fixture.initialize()
        val form = fixture.service.forms.rename(parent)
        form.draft.awaitSaved()
        form.update("Renamed before deletion")
        form.draft.awaitSaved()
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.beforeSessionRead = {
            reading.complete(Unit)
            release.await()
        }
        fixture.service.saveRename(parent, form)
        reading.await()
        val deletion = async { fixture.service.deleteSessionTree(parent.projectId, parent.id) }
        runCurrent()
        assertFalse(deletion.isCompleted, "Deletion waits until the admitted rename leaves the same history lock")
        release.complete(Unit)
        deletion.await()
        runCurrent()
        assertTrue(JsonCodingProjectRepository(fixture.kv, json).sessions(parent.projectId).none { it.id == parent.id })
        assertFalse(form.state.value.available)
        fixture.close()
    }
}
