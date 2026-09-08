package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.storage.JsonRequestPinRepository
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RequestPinViewModelTest {
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
            model.open(Screen.CODING)
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
