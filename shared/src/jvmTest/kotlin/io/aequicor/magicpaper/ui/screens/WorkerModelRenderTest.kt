package io.aequicor.magicpaper.ui.screens

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.CodingUi
import io.aequicor.magicpaper.ui.ModelSettingsFixture
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
class WorkerModelRenderTest {
    @Test fun workerChatShowsAssignedModelAndUpdatesFromThePlan() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val fixture = ModelSettingsFixture()
            val vm = fixture.prepare()
            val project = CodingProject("project", "Проект", "/project", 0)
            val worker = CodingSession("worker", project.id, "Проверка", 0,
                parentSessionId = "parent", stageId = "stage", planId = "plan", engine = CodingEngine.PI)
            val assignment = StageAssignment("openai", "variant:precise", EffortSelection.of(ReasoningEffort.HIGH))
            val stage = Milestone("stage", "Проверка", assignment = assignment)
            val plan = Plan("plan", project.id, "Проверить результат", milestones = listOf(stage))
            val ui = mutableStateOf(CodingUi(projects = listOf(project), current = project,
                sessions = listOf(CodingSessionUi(worker, plan = plan)), currentSessionId = worker.id))
            ImageComposeScene(1100, 680) {
                MagicPaperTheme { Surface { CodingScreen(vm, ui.value, profiles = vm.state.value.availableLlmProfiles) } }
            }.use { scene ->
                var frame = 0L
                fun render() { repeat(5) { scene.render(++frame * 16_000_000L).close(); runCurrent() } }
                fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                fun visibleTexts() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                    .filter { it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 }
                    .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                render()
                assertContains(visibleTexts(), "GPT-5.4 · точные ответы")
                assertFalse("Выбрать модель" in visibleTexts())
                val resolved = assertNotNull(ProfileResolver.selection(ModelSelection(assignment.profileId, assignment.modelId, assignment.effort), vm.state.value.availableLlmProfiles))
                assertContains(visibleTexts(), resolved.effortLabel(ModelDefaults.capability(resolved)))
                File("build/reports/worker-model").mkdirs()
                File("build/reports/worker-model/assigned.png").writeBytes(
                    scene.render(++frame * 16_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })

                val updated = plan.copy(milestones = listOf(stage.copy(assignment = StageAssignment("anthropic", "claude-sonnet-4-6"))))
                ui.value = ui.value.copy(sessions = listOf(CodingSessionUi(worker, plan = updated)))
                render()
                assertContains(visibleTexts(), "Claude Sonnet")
                assertFalse("GPT-5.4 · точные ответы" in visibleTexts())

                val running = updated.copy(milestones = updated.milestones.map { it.copy(status = MilestoneStatus.ACTIVE,
                    attempts = listOf(StageAttempt("attempt", worker.id, assignment, phase = AttemptPhase.EXECUTING))) })
                ui.value = ui.value.copy(sessions = listOf(CodingSessionUi(worker, plan = running)))
                render()
                assertContains(visibleTexts(), "GPT-5.4 · точные ответы")
                assertFalse("Claude Sonnet" in visibleTexts())
            }
        } finally { Dispatchers.resetMain() }
    }
}
