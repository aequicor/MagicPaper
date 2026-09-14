package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
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
import io.aequicor.magicpaper.ui.*
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
class OrchestrationFailureRenderTest {
    @Test fun unavailableVerificationOffersAutomaticCheckAndExplicitSkip() = runTest {
        val parent = CodingSession("parent", "project", "Кнопка новой сессии", 1, planningMode = true)
        val criterion = AcceptanceCriterion("stage/layout", "Кнопка находится слева от меню проекта", environment = EvidenceEnvironment.MANUAL)
        val record = AcceptanceRecord("run", "attempt", "snapshot", listOf(criterion), listOf(
            AcceptanceFinding(criterion.id, CheckStatus.NOT_RUN, criterion.description, "No check")), status = AcceptanceStatus.PARTIAL)
        val issue = PlanningIssue(IssueKind.VERIFICATION, record.summary(), requiresUser = true)
        val stage = Milestone("stage", "Перенести кнопку", attempts = listOf(StageAttempt("attempt", "worker",
            StageAssignment("p", "m"), phase = AttemptPhase.VERIFYING, error = issue, acceptanceRecord = record)))
        val plan = Plan("plan", "project", parent.name, milestones = listOf(stage), parentSessionId = parent.id,
            confirmedRevision = 1, phase = ExecutionPhase.WAITING, issue = issue)
        val request = interactionCandidates(CodingUi(sessions = listOf(CodingSessionUi(parent))), listOf(plan), emptyMap(), emptyMap()).single()
        for (width in listOf(1000, 430)) {
            var submitted = emptyList<PlanningAnswer>()
            ImageComposeScene(width, 720) {
                MagicPaperTheme { Surface {
                    var draft by remember { mutableStateOf(QuestionnaireDraft()) }
                    UserInteractionDock(request, draft, { draft = it }, { submitted = it })
                } }
            }.use { scene ->
                var frame = 0L
                fun render() { repeat(5) { scene.render(++frame * 16_000_000L).close() } }
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                fun nodes() = scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                fun click(tag: String) = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
                    .config[SemanticsActions.OnClick].action!!.invoke()
                render()
                for (id in listOf("retry", "skip_verification", "leave")) {
                    val option = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "questionnaire.option.$id" }
                    assertTrue(option.boundsInRoot.top >= 0 && option.boundsInRoot.bottom <= 720)
                    assertNull(option.config.getOrNull(SemanticsProperties.Disabled))
                }
                val output = File("build/reports/orchestration").apply { mkdirs() }
                File(output, "missing-verification-$width.png").writeBytes(scene.render(++frame * 16_000_000L).use {
                    it.encodeToData()!!.use { data -> data.bytes }
                })
                click("questionnaire.option.skip_verification"); render()
                assertTrue(submitted.isEmpty(), "Selecting an option is a draft")
                click("questionnaire.confirm"); render()
                assertEquals(listOf("skip_verification"), submitted.single().selected)
            }
        }
    }

    @Test fun failedInputUsesQuestionnaireWhileAWorkerContinues() = runTest {
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
            val service = OrchestrationService(store, execution, projects, profiles, settings, textPlanComposer(gateway),
                gateway, backgroundScope, workerDispatcher = StandardTestDispatcher(testScheduler))
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
            val interaction = interactionCandidates(CodingUi(sessions = listOf(CodingSessionUi(parent, plan = plan))),
                listOf(plan), service.states.value, emptyMap()).single()
            var submissions = 0
            val output = File("build/reports/orchestration").apply { mkdirs() }
            for (width in listOf(1000, 430)) {
                ImageComposeScene(width, 560) {
                    MagicPaperTheme { Surface { Column {
                        var draft by remember { mutableStateOf(QuestionnaireDraft()) }
                        OrchestrationStatus(CodingSessionUi(parent, plan = plan, interactions = listOf(interaction)), service, {})
                        UserInteractionDock(interaction, draft, { draft = it }, { submissions++ })
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
                    visible("1/1 · Не удалось обработать сообщение")
                    visible(input.error)
                    visible("Повторить обработку")
                    visible("Сейчас:")
                    visible("Подробнее")
                    assertTrue(nodes.none { text(it) == "Сообщение обрабатывается" })
                    val retry = scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                        .first { it.config.getOrNull(SemanticsProperties.TestTag) == "questionnaire.option.retry" }
                    assertNotNull(retry.config.getOrNull(SemanticsActions.OnClick))
                    assertNull(retry.config.getOrNull(SemanticsProperties.Disabled))
                    retry.config[SemanticsActions.OnClick].action!!.invoke()
                    repeat(3) { scene.render(120_000_000L + it * 16_000_000L).close(); runCurrent() }
                    assertEquals(0, submissions, "Selecting recovery must not execute it")
                    File(output, "input-failure-$width.png").writeBytes(scene.render(112_000_000L).use {
                        it.encodeToData()!!.use { data -> data.bytes }
                    })
                }
            }
        } finally { Dispatchers.resetMain() }
    }
}
