package io.aequicor.magicpaper.domain

import kotlin.test.*

class WorkerModelSelectionTest {
    private val profile = LlmProfile("provider", "Provider", baseUrl = "https://example.com/v1",
        modelId = "gpt-5.4", modelLibraryVersion = 1,
        favoriteModels = listOf("gpt-5.4", "worker-model"),
        variants = listOf(ModelVariant("variant:worker", "Рабочая модель", "gpt-5.4", AdvancedLlmOptions(temperature = .2))))
    private val profiles = listOf(profile)
    private val default = ModelSelection(profile.id, "gpt-5.4")
    private val settings = AppSettings(defaultModel = default)
    private val project = CodingProject("project", "Project", "/project", 0, modelSelection = default)
    private val session = CodingSession("worker", project.id, "Этап", 0, planId = "plan", stageId = "stage", parentSessionId = "parent")
    private val assignment = StageAssignment(profile.id, "variant:worker", EffortSelection.of(ReasoningEffort.HIGH))
    private val stage = Milestone("stage", "Этап", assignment = assignment)
    private val plan = Plan("plan", project.id, "Goal", milestones = listOf(stage))

    private fun resolve(plan: Plan? = this.plan, session: CodingSession = this.session) =
        ProfileResolver.coding(session, project, settings, profiles, plan)

    @Test fun legacyWorkerWithoutSessionChoiceUsesStageAssignment() {
        val resolved = assertNotNull(resolve())
        assertEquals("variant:worker", resolved.selectionKey)
        assertEquals("gpt-5.4", resolved.modelId)
        assertEquals("Рабочая модель", resolved.modelName(resolved.selectionKey))
        assertEquals(assignment.effort, resolved.effortSelectionFor())
    }

    @Test fun reassignedQueuedWorkerDoesNotShowItsStaleSessionChoice() {
        val reassigned = plan.copy(milestones = listOf(stage.copy(assignment = assignment.copy(modelId = "worker-model"))))
        val old = session.copy(modelSelection = ModelSelection(profile.id, "variant:worker"))
        assertEquals("worker-model", resolve(reassigned, old)?.selectionKey)
    }

    @Test fun runningAndRestoredWorkerShowFrozenAttemptIncludingEffortAndOptions() {
        val frozen = assignment.copy(options = AdvancedLlmOptions(temperature = .7))
        val attempt = StageAttempt("attempt", session.id, frozen, phase = AttemptPhase.EXECUTING)
        val running = plan.copy(milestones = listOf(stage.copy(assignment = assignment.copy(modelId = "worker-model"), attempts = listOf(attempt))))
        val resolved = assertNotNull(resolve(running, session.copy(modelSelection = default)))
        assertEquals("variant:worker", resolved.selectionKey)
        assertEquals(frozen.effort, resolved.effortSelectionFor())
        assertEquals(frozen.options, resolved.advanced)
        val restored = kotlinx.serialization.json.Json.decodeFromString<Plan>(
            kotlinx.serialization.json.Json.encodeToString(Plan.serializer(), running))
        assertEquals(resolved, resolve(restored))
    }

    @Test fun mergeUsesItsOwnAssignment() {
        val attempt = StageAttempt("attempt", session.id, assignment, phase = AttemptPhase.INTEGRATING,
            mergePhase = AttemptPhase.EXECUTING, mergeAssignment = StageAssignment(profile.id, "worker-model"))
        assertEquals("worker-model", resolve(plan.copy(milestones = listOf(stage.copy(attempts = listOf(attempt)))))?.selectionKey)
    }

    @Test fun oldStageAgentFieldsAreUsedBeforeGlobalDefaults() {
        val legacy = stage.copy(assignment = null, agentProfileId = profile.id, agentModelId = "worker-model")
        assertEquals("worker-model", resolve(plan.copy(milestones = listOf(legacy)))?.selectionKey)
    }

    @Test fun unavailableAssignmentNeverFallsBackToAnotherModel() {
        for (missing in listOf(assignment.copy(profileId = "removed"), assignment.copy(modelId = "removed"))) {
            assertNull(resolve(plan.copy(milestones = listOf(stage.copy(assignment = missing)))))
        }
    }

    @Test fun ordinarySessionsAndUnrelatedPlansKeepTheirOwnChoice() {
        val own = session.copy(modelSelection = ModelSelection(profile.id, "worker-model"))
        assertEquals("worker-model", resolve(null, own)?.selectionKey)
        assertEquals("worker-model", resolve(plan.copy(id = "another-plan"), own)?.selectionKey)
        assertEquals("worker-model", resolve(plan.copy(projectId = "another-project"), own)?.selectionKey)
        assertEquals("worker-model", resolve(plan, own.copy(stageId = null))?.selectionKey)
        assertEquals(default.modelId, resolve(null)?.selectionKey)
    }
}
