package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.aequicor.magicpaper.data.coding.*
import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.*
import io.aequicor.magicpaper.ui.screens.ProjectsPanel
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
class PlanningProposalRenderTest {
    @Test fun collapsedSummaryKeepsProposalReachableWhileShowingActualWork() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            val kv = InMemoryKeyValueStore()
            val projects = JsonCodingProjectRepository(kv, json)
            val profiles = JsonLlmProfileRepository(kv, json)
            val settings = JsonSettingsRepository(kv, json)
            val store = PlanningStore(JsonPlanningRepository(kv, json))
            val gateway = object : LlmGateway {
                override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String =
                    error("Rendering must not call a model")
            }
            val execution = PlanningExecutionService(store, NoopCodingRuntime, projects, profiles, settings,
                object : MilestoneVerifier {
                    override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) = Verdict(true, "Checked")
                }, scope = backgroundScope)
            val service = OrchestrationService(store, execution, projects, profiles, settings, PlanComposer(gateway), gateway, backgroundScope)
            val project = CodingProject("project", "MagicPaper", "/project", 1)
            val parent = CodingSession("parent", project.id, "Система навыков", 1, planningMode = true)
            projects.save(project); projects.saveSession(parent)
            val stage = Milestone("catalog", "Каталог и импорт по ссылке", status = MilestoneStatus.ACTIVE,
                attempts = listOf(StageAttempt("attempt", "worker", StageAssignment("profile", "model"), phase = AttemptPhase.EXECUTING)))
            val base = Plan("plan", project.id, parent.name, parentSessionId = parent.id, confirmedRevision = 1,
                phase = ExecutionPhase.EXECUTING, intent = ExecutionIntent.RUN, milestones = listOf(stage))
            val followup = Milestone("followup", "Проверка на новых моделях", description = "Проверить оставшуюся работу",
                acceptance = "Поведение подтверждено проверками", assignment = StageAssignment("profile", "Luna"))
            val proposal = PlanProposal("proposal", base.runId, base.tree, base.milestones, base.tree,
                base.milestones + followup, "Предлагается изменить модели для оставшейся работы. Текущие этапы продолжают выполняться.")
            store.save(base.copy(proposal = proposal))
            projects.saveOrchestration(OrchestrationState(parent.id, project.id, activePlanId = base.id))
            service.bootstrap(); runCurrent()
            val output = File("build/reports/proposal-status").apply { mkdirs() }
            for (phase in listOf(ExecutionPhase.EXECUTING, ExecutionPhase.COMPLETE)) {
                val plan = base.copy(proposal = proposal, phase = phase, milestones = listOf(
                    if (phase == ExecutionPhase.COMPLETE) stage.copy(status = MilestoneStatus.DONE) else stage))
                store.save(plan); runCurrent()
                val ui = CodingSessionUi(parent, plan = plan)
                for (width in listOf(1100, 430)) {
                    ImageComposeScene(width, 700) {
                        MagicPaperTheme { Surface { Row {
                            if (width > 700) ProjectsPanel(CodingUi(projects = listOf(project), current = project,
                                sessions = listOf(ui), currentSessionId = parent.id), {}, {}, {}, {}, {}, {}, {}, Modifier.width(300.dp))
                            Column(Modifier.weight(1f)) {
                                OrchestrationStatus(ui, service, {})
                                Text("Ход работы оркестратора", Modifier.padding(16.dp))
                            }
                        } } }
                    }.use { scene ->
                        repeat(6) { scene.render(it * 16_000_000L).close(); runCurrent() }
                        fun nodes() = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                        val labels = nodes().map(::text)
                        assertTrue(labels.none { it.contains("ждёт вашего ответа", ignoreCase = true) || it == "Нужен ваш ответ" })
                        val expected = if (phase == ExecutionPhase.COMPLETE) "Предложение доработки · нужно подтверждение"
                            else "Выполнение этапов · есть предложение доработки"
                        assertTrue(expected in labels, labels.toString())
                        for (label in listOf("Предложение доработки", "Посмотреть")) {
                            val node = nodes().first { text(it) == label }
                            assertTrue(node.boundsInRoot.top >= 0 && node.boundsInRoot.bottom <= 700, label)
                            assertTrue(node.boundsInRoot.left >= 0 && node.boundsInRoot.right <= width, label)
                        }
                        File(output, "${phase.name.lowercase()}-$width.png").writeBytes(scene.render(112_000_000L).use {
                            it.encodeToData()!!.use { data -> data.bytes }
                        })
                        fun action(label: String) = scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                            .first { text(it) == label && it.config.getOrNull(SemanticsActions.OnClick) != null }
                        action("Посмотреть").config[SemanticsActions.OnClick].action!!.invoke()
                        repeat(6) { scene.render((it + 8) * 16_000_000L).close(); runCurrent() }
                        val confirm = action("Подтвердить доработку")
                        assertEquals(phase != ExecutionPhase.COMPLETE, confirm.config.getOrNull(SemanticsProperties.Disabled) != null)
                        assertTrue(nodes().any { text(it).contains(followup.title) })
                        File(output, "${phase.name.lowercase()}-details-$width.png").writeBytes(scene.render(224_000_000L).use {
                            it.encodeToData()!!.use { data -> data.bytes }
                        })
                        action("Закрыть").config[SemanticsActions.OnClick].action!!.invoke()
                        runCurrent()
                    }
                }
            }
        } finally { Dispatchers.resetMain() }
    }

    private fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
}
