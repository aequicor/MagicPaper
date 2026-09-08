package io.aequicor.magicpaper.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.aequicor.magicpaper.ui.screens.ResizableProjectPanels
import androidx.compose.ui.use
import io.aequicor.magicpaper.data.coding.*
import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.screens.CodingChat
import io.aequicor.magicpaper.ui.screens.ProjectsPanel
import io.aequicor.magicpaper.ui.CodingUi
import io.aequicor.magicpaper.ui.components.CodingModelChip
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlanningChatRenderTest {
    @Test fun plannerActivityRendersAfterWorkerHandoff() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val project = CodingProject("p", "MagicPaper", "/project", 1)
            val parent = CodingSession("parent", project.id, "Сессия 3", 1, planningMode = true)
            val child = CodingSession("worker", project.id, "Проверка интерфейса", 2,
                parentSessionId = parent.id, stageId = "check", planId = "plan")
            val plan = Plan("plan", project.id, "Проверить интерфейс", parentSessionId = parent.id, intent = ExecutionIntent.RUN,
                confirmedRevision = 1, milestones = listOf(Milestone("check", "Проверка интерфейса", status = MilestoneStatus.ACTIVE,
                    attempts = listOf(StageAttempt("attempt", child.id, StageAssignment("model", "m"), phase = AttemptPhase.EXECUTING, awaitingPlanner = true)))))
            val draft = CodingDraft(active = true, steps = listOf(
                CodingStep(CodingStepKind.INFO, "Планировщик обрабатывает результат этапа…", running = true),
                CodingStep(CodingStepKind.THINKING, "Сопоставляю результаты проверки и определяю следующий этап.", callId = "thinking")))
            val planner = CodingSessionUi(parent, messages = listOf(CodingMessage("result", CodingRole.AGENT,
                "Исполнитель завершил проверку и передал результат планировщику.", createdAt = 1)), draft = draft, running = true, plan = plan)
            val worker = CodingSessionUi(child, plan = plan)
            assertEquals(CodingSessionStatus.WORKING, planner.status)
            assertEquals(CodingSessionStatus.IDLE, worker.status)
            val ui = CodingUi(projects = listOf(project), current = project, sessions = listOf(planner, worker), currentSessionId = parent.id)
            ImageComposeScene(1100, 680) {
                MagicPaperTheme { Surface { Row {
                    ProjectsPanel(ui, {}, {}, {}, {}, {}, {}, {}, modifier = Modifier.width(300.dp))
                    Box(Modifier.weight(1f)) {
                        CodingChat(project, planner, true, true, { _, _ -> }, {}, { _, _ -> })
                    }
                } } }
            }.use { scene ->
                repeat(5) { scene.render(it * 16_000_000L).close(); runCurrent() }
                val output = File("build/reports/planning-chat").apply { mkdirs() }
                File(output, "planner-handoff.png").writeBytes(scene.render(96_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun sidebarWidthChangesByDraggingDivider() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            var measuredWidth = 0
            ImageComposeScene(1000, 620) {
                MagicPaperTheme { Surface { ResizableProjectPanels(sidebar = { modifier ->
                    Box(modifier.fillMaxHeight().onSizeChanged { measuredWidth = it.width })
                }) { Text("Чат") } } }
            }.use { scene ->
                repeat(5) { scene.render(it * 16_000_000L).close(); runCurrent() }
                assertEquals(272, measuredWidth)
                scene.sendPointerEvent(PointerEventType.Press, Offset(276f, 300f))
                scene.sendPointerEvent(PointerEventType.Move, Offset(316f, 300f))
                scene.sendPointerEvent(PointerEventType.Move, Offset(396f, 300f))
                scene.sendPointerEvent(PointerEventType.Release, Offset(396f, 300f))
                repeat(5) { scene.render((it + 6) * 16_000_000L).close(); runCurrent() }
                assertTrue(measuredWidth > 340, "Dragged sidebar width: $measuredWidth")
                assertTrue(measuredWidth <= 600)
            }
        } finally { Dispatchers.resetMain() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun projectAndPlanStayVisibleWhenStagesScroll() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val project = CodingProject("p", "Закреплённый проект", "/project", 1)
            val parent = CodingSession("plan", "p", "Закреплённый план", 1, planningMode = true)
            val sessions = listOf(CodingSessionUi(parent)) + (1..30).map {
                CodingSessionUi(CodingSession("stage-$it", "p", "Этап $it", 1, parentSessionId = "plan", stageId = "$it"))
            }
            val state = LazyListState()
            ImageComposeScene(320, 620) {
                MagicPaperTheme { Surface { ProjectsPanel(CodingUi(projects = listOf(project), current = project, sessions = sessions),
                    {}, {}, {}, {}, {}, {}, {}, listState = state) } }
            }.use { scene ->
                repeat(5) { scene.render(it * 16_000_000L).close(); runCurrent() }
                state.scrollToItem(12)
                repeat(5) { scene.render((it + 6) * 16_000_000L).close(); runCurrent() }
                assertTrue(state.firstVisibleItemIndex >= 10)
                assertEquals(0, state.layoutInfo.visibleItemsInfo.single { it.key == "project-p" }.offset)
                fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                val visible = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                    .filter { it.boundsInRoot.height > 0 }
                fun title(text: String) = visible.single {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
                }.boundsInRoot
                val projectTitle = title(project.name)
                val planTitle = title("🔀 ${parent.name}")
                assertTrue(projectTitle.top >= 0)
                assertTrue(planTitle.top >= projectTitle.bottom, "The pinned plan must remain below its project")
                assertTrue(planTitle.bottom <= 620, "Both pinned titles must remain visible")
                val bytes = scene.render(200_000_000L).use { image -> image.encodeToData()!!.use { it.bytes } }
                File("build/reports/planning-chat/sticky-sidebar.png").apply { parentFile.mkdirs() }.writeBytes(bytes)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun nestedSessionsRenderInSidebar() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val project = CodingProject("p", "MagicPaper", "/project", 1)
            val parent = CodingSession("s", "p", "Редактор документов", 1, planningMode = true)
            val child = CodingSession("c", "p", "Редактор · Экспорт PDF", 2, parentSessionId = "s", stageId = "export", planId = "plan")
            val plan = Plan("plan", project.id, "Редактор документов", parentSessionId = parent.id,
                intent = ExecutionIntent.RUN, confirmedRevision = 1, milestones = listOf(
                    Milestone("export", "Экспорт PDF", status = MilestoneStatus.ACTIVE), Milestone("check", "Проверка экспорта", dependsOn = listOf("export"))))
            val queued = child.copy(id = "queued", stageId = "check", name = "Редактор · Проверка экспорта")
            val ui = CodingUi(projects = listOf(project, project.copy(id = "other", name = "Личный сайт")), current = project,
                sessions = listOf(CodingSessionUi(parent, plan = plan), CodingSessionUi(child, running = true, plan = plan),
                    CodingSessionUi(queued, plan = plan), CodingSessionUi(parent.copy(id = "ordinary", name = "Исправления", planningMode = false))), currentSessionId = "c")
            for (width in listOf(280, 390)) ImageComposeScene(width, 620) {
                MagicPaperTheme { Surface { ProjectsPanel(ui, {}, {}, {}, {}, {}, {}, {}) } }
            }.use { scene ->
                repeat(5) { scene.render(it * 16_000_000L).close(); runCurrent() }
                val bytes = scene.render(96_000_000L).use { image -> image.encodeToData()!!.use { it.bytes } }
                val output = File("build/reports/planning-chat").apply { mkdirs() }
                File(output, "sidebar-$width.png").writeBytes(bytes)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun questionsAndGraphRenderInsideChatAtBothWidths() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val kv = InMemoryKeyValueStore()
            val store = PlanningStore(JsonPlanningRepository(kv, Json))
            val repo = JsonCodingProjectRepository(kv, Json)
            val profiles = JsonLlmProfileRepository(kv, Json)
            val settings = JsonSettingsRepository(kv, Json)
            val gateway = object : LlmGateway { override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = error("No network in render test") }
            val verifier = object : MilestoneVerifier { override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) = Verdict(true, "Checked") }
            val execution = PlanningExecutionService(store, NoopCodingRuntime, repo, profiles, settings, verifier, scope = backgroundScope)
            val service = PlanningChatService(store, execution, repo, profiles, settings, PlanComposer(gateway), gateway, backgroundScope)
            val project = CodingProject("project", "Документы", "/project", 1)
            val session = CodingSession("parent", project.id, "Редактор", 1, planningMode = true)
            val stages = listOf(Milestone("one", "Редактор", description = "Создать редактор", acceptance = "Открывает документы"), Milestone("two", "Экспорт", dependsOn = listOf("one"), acceptance = "Сохраняет PDF"))
            val plan = Plan("plan", project.id, "Редактор документов с экспортом", parentSessionId = session.id, milestones = stages,
                tree = listOf(DecisionNode("root", "Редактор документов", DecisionKind.GOAL, stages.map { it.id })) + stages.map { DecisionNode(it.id, it.title, DecisionKind.STAGE, stageId = it.id) })
            store.save(plan)
            val questions = listOf(PlanningQuestion("format", "Какой формат нужен первым?", QuestionKind.SINGLE, listOf(QuestionOption("pdf", "PDF"), QuestionOption("doc", "DOCX"))),
                PlanningQuestion("features", "Какие возможности включить?", QuestionKind.MULTIPLE, listOf(QuestionOption("edit", "Редактирование"), QuestionOption("export", "Экспорт"))),
                PlanningQuestion("criteria", "Как проверить готовность?", QuestionKind.TEXT))
            for (width in listOf(1000, 390)) for (graph in listOf(false, true)) {
                val message = CodingMessage("message", CodingRole.AGENT, if (graph) "План готов к подтверждению." else "Уточним требования к редактору.", createdAt = 1,
                    planning = PlanningChatBlock(plan.id, questions = if (graph) emptyList() else questions, graph = graph))
                ImageComposeScene(width, 1100) {
                    MagicPaperTheme { Surface(Modifier.fillMaxSize()) {
                        CodingChat(project, CodingSessionUi(session, messages = listOf(message)), false, true, { _, _ -> }, {}, { _, _ -> },
                            planningService = service, onPlanning = {}, modelChip = { CodingModelChip(LlmProfile("model", "GPT-5.6-Terra", modelId = "gpt-5.6-terra"), false, {}); TextButton({}) { Text("AUTO ▾") } })
                    } }
                }.use { scene ->
                    repeat(5) { scene.render(it * 16_000_000L).close(); runCurrent() }
                    val output = File("build/reports/planning-chat").apply { mkdirs() }
                    val bytes = scene.render(96_000_000L).use { image -> image.encodeToData()!!.use { it.bytes } }
                    assertTrue(bytes.size > 1000)
                    File(output, "${if (graph) "graph" else "questions"}-$width.png").writeBytes(bytes)
                }
            }
        } finally { Dispatchers.resetMain() }
    }
}
