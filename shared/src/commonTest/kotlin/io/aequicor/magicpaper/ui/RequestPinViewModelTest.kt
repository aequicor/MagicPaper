package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.storage.JsonRequestPinRepository
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RequestPinViewModelTest {
    @Test fun failedDeletionSignalsEveryOwnerAndPreservesHistoryPinsAndProject() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            for (action in listOf("project", "all", "subtree")) {
                val f = ModelSettingsFixture()
                val repo = JsonCodingProjectRepository(f.kv, f.json)
                val pins = JsonRequestPinRepository(f.kv, f.json)
                val aborted = mutableSetOf<String>()
                val runtime = object : CodingRuntime {
                    override val supported = true
                    override val rootPath = "/tmp/project"
                    override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
                    override fun ensureReady() = flowOf(RuntimeStatus(RuntimePhase.READY))
                    override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = emptyFlow<CodingEvent>()
                    override fun abort(sessionId: String) {
                        aborted += sessionId
                        if (sessionId == "root") error("Остановка не подтверждена")
                    }
                    override fun abortAll() = Unit
                    override suspend fun uninstall() = Unit
                }
                repo.save(CodingProject("project", "Проект", "/tmp/project", 1))
                listOf(CodingSession("root", "project", "Корень", 1),
                    CodingSession("child", "project", "Потомок", 2, parentSessionId = "root")).forEach { session ->
                    repo.saveSession(session)
                    repo.saveMessages("project", session.id, listOf(CodingMessage("input-${session.id}", CodingRole.USER, "Запрос", createdAt = 1)))
                }
                val model = f.prepare(codingRuntime = runtime, codingProjects = repo, requestPinRepository = pins)
                try {
                    model.open(Screen.CHAT); model.setViewingCoding(true); advanceUntilIdle()
                    model.selectCodingSession("root"); advanceUntilIdle()
                    val key = PinConversation("root", "project")
                    assertTrue(pins.load(key).isNotEmpty())
                    when (action) {
                        "project" -> model.deleteCodingProject("project")
                        "all" -> model.deleteAllCodingSessions("project")
                        else -> model.deleteCodingSession("root")
                    }
                    advanceUntilIdle()
                    assertEquals(setOf("root", "child"), aborted, action)
                    assertEquals(2, repo.sessions("project").size, action)
                    assertTrue(repo.messages("project", "root").isNotEmpty(), action)
                    assertTrue(pins.load(key).isNotEmpty(), action)
                    assertTrue(repo.all().any { it.id == "project" }, action)
                    assertEquals("Остановка не подтверждена", model.state.value.notice, action)
                } finally { model.shutdownCoding() }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun planningSessionPinsOnlyClassifiedRequestsAndRetainsTheirClarifications() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var vm: MagicPaperViewModel? = null
        try {
            val f = ModelSettingsFixture()
            val repo = JsonCodingProjectRepository(f.kv, f.json)
            val pins = JsonRequestPinRepository(f.kv, f.json)
            repo.save(CodingProject("project", "Проект", "/tmp/project", 1))
            repo.saveSession(CodingSession("coding", "project", "Сессия", 1, planningMode = true, engine = CodingEngine.PI))
            val task = CodingMessage("task", CodingRole.USER, "Добавь кнопку", createdAt = 1,
                planning = PlanningChatBlock("plan", inputIntent = UserTurnIntent.REFINE))
            val question = CodingMessage("question", CodingRole.USER, "Что это значит?", createdAt = 2,
                planning = PlanningChatBlock("plan", inputIntent = UserTurnIntent.DISCUSS))
            val pending = CodingMessage("pending", CodingRole.USER, "Я имел в виду слева", createdAt = 3)
            repo.saveMessages("project", "coding", listOf(task, question, pending))
            val model = f.prepare(codingProjects = repo, requestPinRepository = pins).also { vm = it }
            model.open(Screen.CHAT); model.setViewingCoding(true); advanceUntilIdle()
            val key = PinConversation("coding", "project")
            assertEquals(listOf("task"), pins.load(key).map { it.source.id })
            repo.saveMessages("project", "coding", listOf(task, question,
                pending.copy(planning = PlanningChatBlock("plan", inputIntent = UserTurnIntent.CLARIFY))))
            model.selectCodingProject("project"); advanceUntilIdle()
            val group = model.requestPins!!.groups.value.getValue(key).single()
            assertEquals("task", group.request.messageId)
            assertEquals("pending", group.clarifications.single().messageId)
            assertEquals(2, f.calls.size)
        } finally {
            vm?.shutdownCoding()
            Dispatchers.resetMain()
        }
    }

    @Test fun chatAnswerUsesOverrideButPinsUseOperationalDefaultAndOldChatsAreLazy() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var vm: MagicPaperViewModel? = null
        try {
            val f = ModelSettingsFixture()
            val pins = JsonRequestPinRepository(f.kv, f.json)
            f.chats.save(ChatSession("older", "История", 0, 0,
                messages = listOf(ChatMessage("old-input", ChatRole.USER, "Старый запрос", 0))))
            val model = f.prepare(requestPinRepository = pins).also { vm = it }
            assertTrue(f.calls.isEmpty(), "Unopened histories should not consume model calls")
            model.selectChatModel(ModelSelection("anthropic", "claude-sonnet-4-6"))
            model.send("Напиши стихотворение о дожде")
            advanceUntilIdle()
            assertEquals(1, f.calls.count { it.id == "anthropic" })
            assertEquals(1, f.calls.count { it.id == "openai" })
            assertEquals("Краткий запрос", pins.load(PinConversation("first")).single().summary)
            model.selectSession("older")
            advanceUntilIdle()
            assertEquals(2, f.calls.count { it.id == "openai" })
            model.selectSession("first")
            advanceUntilIdle()
            assertEquals(3, f.calls.size, "Reopening reuses successful analysis")
            model.deleteSession("older")
            advanceUntilIdle()
            assertTrue(pins.load(PinConversation("older")).isEmpty())
        } finally {
            vm?.shutdownCoding()
            Dispatchers.resetMain()
        }
    }

    @Test fun codingSessionUsesSharedPinsAndChangingDeliveryStatusDoesNotResummarise() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var vm: MagicPaperViewModel? = null
        try {
            val f = ModelSettingsFixture()
            val repo = JsonCodingProjectRepository(f.kv, f.json)
            val pins = JsonRequestPinRepository(f.kv, f.json)
            repo.save(CodingProject("project", "Проект", "/tmp/project", 1))
            repo.saveSession(CodingSession("coding", "project", "Сессия", 1, engine = CodingEngine.PI,
                modelSelection = ModelSelection("anthropic", "claude-sonnet-4-6")))
            val input = CodingMessage("input", CodingRole.USER, "Выполнить этап", createdAt = 1, pendingDelivery = true)
            repo.saveMessages("project", "coding", listOf(input))
            val model = f.prepare(codingProjects = repo, requestPinRepository = pins).also { vm = it }
            assertTrue(f.calls.isEmpty())
            model.open(Screen.CHAT); model.setViewingCoding(true)
            advanceUntilIdle()
            assertEquals("openai", f.calls.single().id)
            assertEquals(1, model.requestPins?.groups?.value?.get(PinConversation("coding", "project"))?.size)
            repo.saveMessages("project", "coding", listOf(input.copy(pendingDelivery = false)))
            model.selectCodingProject("project")
            advanceUntilIdle()
            assertEquals(1, f.calls.size)
            model.deleteAllCodingSessions("project")
            advanceUntilIdle()
            assertTrue(pins.load(PinConversation("coding", "project")).isEmpty())
        } finally {
            vm?.shutdownCoding()
            Dispatchers.resetMain()
        }
    }
}
