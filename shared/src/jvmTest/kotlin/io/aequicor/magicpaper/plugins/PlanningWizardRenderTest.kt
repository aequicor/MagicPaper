package io.aequicor.magicpaper.plugins

import androidx.compose.foundation.layout.fillMaxSize
import io.aequicor.magicpaper.plugins.builtin.StageDetailsContent
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
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

@OptIn(ExperimentalCoroutinesApi::class)
class PlanningWizardRenderTest {
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
                val plugin = CodingPlanningPlugin(store, PlanComposer(gateway), DossierResearcher(gateway, search), service, NoopCodingRuntime, null, profiles, settings)
                ImageComposeScene(width, 1000) {
                    MagicPaperTheme { Surface { plugin.SessionPanel(project, Modifier) } }
                }.use { scene ->
                    repeat(5) { scene.render(it * 16_000_000L).close(); runCurrent() }
                    val bytes = scene.render(96_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } }
                    assertTrue(bytes.size > 1000)
                    File(output, "${step.name.lowercase()}-$width.png").writeBytes(bytes)
                }
            }
        } finally { Dispatchers.resetMain() }
    }
}
