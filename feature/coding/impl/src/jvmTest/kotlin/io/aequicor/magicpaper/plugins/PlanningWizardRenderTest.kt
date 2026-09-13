package io.aequicor.magicpaper.plugins

import io.aequicor.magicpaper.data.storage.PersistentDraftValue

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import io.aequicor.magicpaper.plugins.builtin.StageDetailsContent
import io.aequicor.magicpaper.plugins.builtin.NodeEditor
import io.aequicor.magicpaper.plugins.builtin.PlanningNodeDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.use
import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.plugins.builtin.CodingPlanningPlugin
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
class PlanningWizardRenderTest {
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }

    private fun SemanticsNode.texts(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + children.flatMap { it.texts() }

    private fun ImageComposeScene.invokeAction(label: String) {
        val node = nodes().single { it.config.getOrNull(SemanticsActions.OnClick) != null && label in it.texts() }
        node.config[SemanticsActions.OnClick].action!!.invoke()
    }

    @Test fun nodeEditorPreservesBranchCallbacks() {
        val root = DecisionNode("root", "Цель", DecisionKind.GOAL)
        val planState = mutableStateOf(Plan("plan", "project", "Цель", tree = listOf(root)))
        val selectedNode = mutableStateOf(root)
        val draftScope = CoroutineScope(Dispatchers.Unconfined)
        val drafts = InMemoryDraftRepository()
        val owners = mutableMapOf<String, PersistentDraftValue<PlanningNodeDraft>>()
        ImageComposeScene(720, 700) {
            MagicPaperTheme {
                val owner = owners.getOrPut(selectedNode.value.id) { PersistentDraftValue(drafts, selectedNode.value.id,
                    PlanningNodeDraft.serializer(), PlanningNodeDraft(title = selectedNode.value.title), draftScope) }
                NodeEditor(planState.value, selectedNode.value, emptyList(), owner) { transform ->
                    planState.value = transform(planState.value)
                }
            }
        }.use { scene ->
            fun render() { repeat(4) { scene.render(it * 16_000_000L).close() } }
            render()
            scene.invokeAction("+ Этап")
            assertEquals(1, planState.value.milestones.size)
            assertEquals(1, planState.value.tree.count { it.kind == DecisionKind.STAGE })
            scene.invokeAction("+ Выбор подхода")
            val choice = planState.value.tree.single { it.kind == DecisionKind.CHOICE }
            selectedNode.value = choice
            render()
            scene.invokeAction("Удалить узел и его ветвь")
            assertTrue(planState.value.tree.none { it.id == choice.id })
            assertTrue(planState.value.tree.none { it.kind == DecisionKind.OPTION })
        }
        draftScope.cancel()
    }

    @Test fun rendersStagePromptAndLiveConversation() {
        val stage = Milestone("s", "Редактор документов", description = "Добавить экспорт документов в PDF.", acceptance = "Содержимое и форматирование сохраняются.",
            status = MilestoneStatus.ACTIVE, attempts = listOf(StageAttempt("a", "session", StageAssignment("agent", "model"),
                phase = AttemptPhase.EXECUTING, prompt = "Общая цель: редактор. Этап: экспорт PDF.", steps = listOf(
                    CodingStep(CodingStepKind.ANSWER, "Проверяю существующий экспорт и сохранение форматирования."),
                    CodingStep(CodingStepKind.TOOL, "Чтение файлов редактора", tool = "read", result = "Найден модуль экспорта", running = false),
                    CodingStep(CodingStepKind.THINKING, "Сопоставляю результат с критериями готовности.")
                ))))
        val node = DecisionNode("s", stage.title, DecisionKind.STAGE, stageId = "s")
        val plan = Plan("p", "project", "Редактор", milestones = listOf(stage), tree = listOf(node))
        for (width in listOf(820, 390)) ImageComposeScene(width, 900) {
            MagicPaperTheme { StageDetailsContent(plan, node, {}, Modifier.fillMaxSize()) }
        }.use { scene ->
            repeat(3) { scene.render(it * 16_000_000L).close() }
            val output = File("build/reports/planning-wizard").apply { mkdirs() }
            scene.render(64_000_000L).use { image -> File(output, "stage-dialog-$width.png").writeBytes(image.encodeToData()!!.use { it.bytes }) }
        }
    }

    @Test fun rendersFourStepsOnDesktopAndPhone() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val output = File("build/reports/planning-wizard").apply { mkdirs() }
            for (step in PlanningStep.entries) for (width in listOf(1000, 390)) {
                val kv = InMemoryKeyValueStore()
                val store = PlanningStore(JsonPlanningRepository(kv, Json))
                val profile = LlmProfile("agent", "Планировщик", baseUrl = "http://test/v1", modelId = "model", favoriteModels = listOf("model"), modelLibraryVersion = 1)
                val profiles = JsonLlmProfileRepository(kv, Json).also { it.save(profile) }
                val settings = JsonSettingsRepository(kv, Json).also { it.save(AppSettings(activeLlmProfileId = profile.id)) }
                val gateway = object : LlmGateway {
                    override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = error("Rendering must not call model")
                }
                val search = object : SearchEngine {
                    override val provider = SearchProvider.AUTO
                    override val displayName = "Авто"
                    override fun isConfigured(settings: AppSettings) = true
                    override suspend fun search(query: String, settings: AppSettings, limit: Int): List<SearchHit> = error("Rendering must not search")
                }
                val verifier = object : MilestoneVerifier {
                    override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) = Verdict(true, "Готово")
                }
                val project = CodingProject("project", "Редактор документов", "/fake", 1)
                val milestones = listOf(Milestone("s1", "Редактор и форматы", acceptance = "Открывает документы"), Milestone("s2", "Проверки и экспорт", acceptance = "Экспортирует PDF", dependsOn = listOf("s1")))
                if (step != PlanningStep.GOAL) store.save(Plan("plan", project.id, "Создать удобный редактор документов", wizardStep = step,
                    milestones = if (step == PlanningStep.CLARIFY) emptyList() else if (step == PlanningStep.STATUS) milestones.mapIndexed { index, milestone ->
                        if (index != 0) milestone else milestone.copy(status = MilestoneStatus.ACTIVE, attempts = listOf(StageAttempt(
                            "attempt", "session", StageAssignment("agent", "model"), phase = AttemptPhase.EXECUTING,
                            steps = List(30) { CodingStep(CodingStepKind.INFO, "") })))
                    } else milestones,
                    dialogue = listOf(PlanningMessage("q", "assistant", "Какие форматы документов нужны?\n\nКак будем проверять готовность?", listOf(CodingStep(CodingStepKind.INFO, "Поиск источников завершён")))),
                    tree = listOf(DecisionNode("root", "Редактор документов", DecisionKind.GOAL, milestones.map { it.id })) + milestones.map { DecisionNode(it.id, it.title, DecisionKind.STAGE, stageId = it.id) }))
                val service = PlanningExecutionService(store, NoopCodingRuntime, null, profiles, settings, verifier, scope = backgroundScope)
                val drafts = InMemoryDraftRepository()
                val plugin = CodingPlanningPlugin(store, textPlanComposer(gateway), DossierResearcher(gateway, search), service, NoopCodingRuntime, null, profiles, settings, drafts, backgroundScope)
                ImageComposeScene(width, 1000) {
                    MagicPaperTheme { Surface { plugin.SessionPanel(project, Modifier) } }
                }.use { scene ->
                    repeat(5) { scene.render(it * 16_000_000L).close(); runCurrent() }
                    val bytes = scene.render(96_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } }
                    assertTrue(bytes.size > 1000)
                    File(output, "${step.name.lowercase()}-$width.png").writeBytes(bytes)
                    if (step == PlanningStep.GOAL && width == 1000) {
                        val staleEdit = requireNotNull(scene.nodes().first {
                            it.config.getOrNull(SemanticsActions.SetText) != null
                        }.config[SemanticsActions.SetText].action)
                        staleEdit(androidx.compose.ui.text.AnnotatedString("Удаляемый черновик"))
                        runCurrent(); plugin.flushDrafts()
                        assertTrue(drafts.keys("planning:${project.id}:").isNotEmpty())
                        plugin.removeProjectDrafts(project.id, null)
                        staleEdit(androidx.compose.ui.text.AnnotatedString("Поздний callback"))
                        runCurrent(); plugin.flushDrafts()
                        assertTrue(drafts.keys("planning:${project.id}:").isEmpty())
                        repeat(3) { scene.render((it + 7) * 16_000_000L).close(); runCurrent() }
                        assertTrue(scene.nodes().any { "Проект удалён" in it.texts() })
                    }
                }
            }
        } finally { Dispatchers.resetMain() }
    }
}
