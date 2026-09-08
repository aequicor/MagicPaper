package io.aequicor.magicpaper.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.use
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.data.coding.*
import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.CodingUi
import io.aequicor.magicpaper.ui.components.OrchestrationMessageRoute
import io.aequicor.magicpaper.ui.screens.CodingChat
import io.aequicor.magicpaper.ui.screens.ProjectsPanel
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OrchestrationRenderTest {
    @Test fun namedRoutesAndQuestionContextRenderAtDesktopAndPhoneWidths() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            val kv = InMemoryKeyValueStore()
            val projects = JsonCodingProjectRepository(kv, json)
            val profiles = JsonLlmProfileRepository(kv, json)
            val settings = JsonSettingsRepository(kv, json)
            val store = PlanningStore(JsonPlanningRepository(kv, json))
            val gateway = object : LlmGateway {
                override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>) = error("Rendering must not call a model")
            }
            val execution = PlanningExecutionService(store, NoopCodingRuntime, projects, profiles, settings,
                object : MilestoneVerifier {
                    override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) = Verdict(true, "Checked")
                }, scope = backgroundScope)
            val service = OrchestrationService(store, execution, projects, profiles, settings, PlanComposer(gateway), gateway, backgroundScope)
            val project = CodingProject("project", "MagicPaper", "/project", 1)
            val parent = CodingSession("parent", project.id, "Личный кабинет", 1, planningMode = true)
            projects.save(project); projects.saveSession(parent)
            val first = Milestone("login", "Вход по email", status = MilestoneStatus.ACTIVE, acceptance = "Вход подтверждён кодом",
                attempts = listOf(StageAttempt("attempt-login", "worker-login", StageAssignment("profile", "model"),
                    phase = AttemptPhase.EXECUTING, waitingForUser = "question")))
            val second = Milestone("export", "Экспорт документов", status = MilestoneStatus.ACTIVE, acceptance = "Файл открывается",
                attempts = listOf(StageAttempt("attempt-export", "worker-export", StageAssignment("profile", "model"), phase = AttemptPhase.EXECUTING)))
            val plan = Plan("plan", project.id, "Личный кабинет", parentSessionId = parent.id, confirmedRevision = 1,
                phase = ExecutionPhase.EXECUTING, intent = ExecutionIntent.RUN, milestones = listOf(first, second),
                tree = listOf(DecisionNode("root", "Личный кабинет", DecisionKind.GOAL, listOf("login", "export"))) +
                    listOf(first, second).map { DecisionNode(it.id, it.title, DecisionKind.STAGE, stageId = it.id) })
            store.save(plan)
            projects.saveOrchestration(OrchestrationState(parent.id, project.id, activePlanId = plan.id,
                questions = listOf(OrchestrationQuestion("question", plan.id, "Как долго действует код входа?",
                    listOf(PlanningQuestion("expiry", "Как долго должен действовать код входа?", QuestionKind.SINGLE,
                        listOf(QuestionOption("five", "5 минут"), QuestionOption("ten", "10 минут")))),
                    "worker-login", listOf("login"), "Этап 1 · Вход по email"))))
            service.bootstrap(); runCurrent()
            service.prepareSessions(store.planFor(plan.id)!!); runCurrent()
            val from = projects.sessions(project.id).first { it.id == parent.id }
            val worker = projects.sessions(project.id).first { it.id == "worker-login" }
            service.append(project.id, parent.id, CodingMessage("route", CodingRole.AGENT,
                "Нужно уточнить срок действия кода, прежде чем завершать вход по email.", createdAt = 2,
                route = MessageRoute(SessionAddress(worker.id, worker.name, worker.subtitle()),
                    SessionAddress(from.id, from.name, from.subtitle()), kind = "Вопрос", stageLabel = "Этап 1 · Вход по email")))
            runCurrent()
            val history = projects.messages(project.id, parent.id)
            assertEquals("question", history.pendingPlanningQuestion()?.id)
            val ui = CodingSessionUi(from, history, plan = store.planFor(plan.id))
            val own = projects.sessions(project.id).map { if (it.id == parent.id) ui else CodingSessionUi(it, plan = store.planFor(plan.id)) }
            val output = File("build/reports/orchestration").apply { mkdirs() }
            for ((width, height) in listOf(1280 to 900, 430 to 900)) {
                ImageComposeScene(width, height) {
                    MagicPaperTheme { Surface { Row {
                        if (width > 700) ProjectsPanel(CodingUi(projects = listOf(project), current = project,
                            sessions = own, currentSessionId = parent.id), {}, {}, {}, {}, {}, {}, {}, Modifier.width(300.dp))
                        Box(Modifier.weight(1f)) {
                            CodingChat(project, ui, false, true, { _, _ -> }, {}, { _, _ -> },
                                planningService = service, allowQueue = true)
                        }
                    } } }
                }.use { scene ->
                    repeat(6) { scene.render(it * 16_000_000L).close(); runCurrent() }
                    File(output, "orchestrator-$width.png").writeBytes(scene.render(112_000_000L).use {
                        it.encodeToData()!!.use { data -> data.bytes }
                    })
                }
            }
            val longName = "Реализовать полноценную систему скилов, репозитория скилов, дообучения в процессе работы"
            val longRoute = CodingMessage("long-route", CodingRole.AGENT, "", createdAt = 3,
                route = MessageRoute(SessionAddress("parent", longName, "Оркестратор 1"),
                    SessionAddress("worker", "Исполнение и управление в MagicPaper", "Исполнитель · Этап 5", "$longName · Оркестратор 1"),
                    kind = "Задание", stageLabel = "Этап 5 · Исполнение и управление в MagicPaper"))
            for ((width, height) in listOf(700 to 480, 430 to 700)) {
                ImageComposeScene(width, height) {
                    MagicPaperTheme { Surface {
                        Box(Modifier.padding(16.dp)) { OrchestrationMessageRoute(longRoute, null) {} }
                    } }
                }.use { scene ->
                    repeat(6) { scene.render(it * 16_000_000L).close(); runCurrent() }
                    File(output, "long-route-$width.png").writeBytes(scene.render(112_000_000L).use {
                        it.encodeToData()!!.use { data -> data.bytes }
                    })
                }
            }
        } finally { Dispatchers.resetMain() }
    }
}
