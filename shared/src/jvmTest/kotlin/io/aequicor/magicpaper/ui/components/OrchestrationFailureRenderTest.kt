package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.use
import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.data.planning.JsonPlanningRepository
import io.aequicor.magicpaper.data.planning.PlanningStore
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
class OrchestrationFailureRenderTest {
    @Test fun collapsedSummaryShowsFailureAndRetryWhileAWorkerContinues() = runTest {
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
                    override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) =
                        Verdict(true, "Checked")
                }, scope = backgroundScope)
            val service = OrchestrationService(store, execution, projects, profiles, settings, PlanComposer(gateway),
                gateway, backgroundScope)
            val project = CodingProject("project", "MagicPaper", "/project", 1)
            val parent = CodingSession("parent", project.id, "Система навыков", 1, planningMode = true)
            projects.save(project); projects.saveSession(parent)
            val stage = Milestone("catalog", "Каталог навыков", status = MilestoneStatus.ACTIVE,
                attempts = listOf(StageAttempt("attempt", "worker", StageAssignment("profile", "model"),
                    phase = AttemptPhase.EXECUTING)))
            val plan = Plan("plan", project.id, parent.name, parentSessionId = parent.id, confirmedRevision = 1,
                phase = ExecutionPhase.EXECUTING, intent = ExecutionIntent.RUN, milestones = listOf(stage))
            store.save(plan)
            val input = OrchestrationInput("input", "Переключи выполнение на более дешёвые модели", 2,
                status = OrchestrationInputStatus.FAILED,
                error = "Input exceeds the maximum length of 1048576 characters.")
            projects.saveOrchestration(OrchestrationState(parent.id, project.id, activePlanId = plan.id, inputs = listOf(input)))
            service.bootstrap(); runCurrent()
            val output = File("build/reports/orchestration").apply { mkdirs() }
            for (width in listOf(1000, 430)) {
                ImageComposeScene(width, 560) {
                    MagicPaperTheme { Surface { Column {
                        OrchestrationStatus(CodingSessionUi(parent, plan = plan), service, {})
                    } } }
                }.use { scene ->
                    repeat(6) { scene.render(it * 16_000_000L).close(); runCurrent() }
                    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                    val nodes = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                    fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
                    fun visible(label: String) = nodes.first { text(it).contains(label) }.also {
                        assertTrue(it.boundsInRoot.top >= 0 && it.boundsInRoot.bottom <= 560, "$label must be visible")
                        assertTrue(it.boundsInRoot.left >= 0 && it.boundsInRoot.right <= width, "$label must fit")
                    }
                    visible("Ошибка обработки сообщения")
                    visible("Контекст запроса превысил допустимый размер")
                    visible("Повторить обработку")
                    visible("Сейчас:")
                    visible("Подробнее")
                    assertTrue(nodes.none { text(it) == "Оркестратор обрабатывает сообщение" })
                    val retry = scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                        .first { text(it) == "Повторить обработку" }
                    assertNotNull(retry.config.getOrNull(SemanticsActions.OnClick))
                    assertNull(retry.config.getOrNull(SemanticsProperties.Disabled))
                    File(output, "input-failure-$width.png").writeBytes(scene.render(112_000_000L).use {
                        it.encodeToData()!!.use { data -> data.bytes }
                    })
                }
            }
        } finally { Dispatchers.resetMain() }
    }
}
