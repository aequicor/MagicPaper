package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.data.storage.JsonRequestPinRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RequestPinServiceTest {
    private val key = PinConversation("chat")
    private val profile = LlmProfile("default", "Операционная", baseUrl = "http://example.test/v1", modelId = "model")
    private fun input(id: String, text: String = id) = PinMessage(id, text, true)

    private class Fixture(scope: CoroutineScope, val answer: suspend (String) -> String) {
        val store = InMemoryKeyValueStore()
        val repo = JsonRequestPinRepository(store, Json { ignoreUnknownKeys = true })
        val calls = mutableListOf<Pair<LlmProfile, List<LlmMessage>>>()
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
                calls += profile to messages
                val source = Json.parseToJsonElement(messages.last().content).jsonObject.getValue("message").jsonObject
                return answer(source.getValue("id").jsonPrimitive.content)
            }
        }
        val service = RequestPinService(repo, gateway, scope)
    }

    @Test fun groupsRequestsAndClarificationsInOrderAndCachesAcrossRestart() = runTest {
        val f = Fixture(this) { id -> """{"summary":"Пересказ $id","newRequest":${id != "a2"}}""" }
        val messages = listOf(input("a1"), PinMessage("question", "Какой цвет?", false), input("a2", "Синий"),
            input("b1"), input("returnA", "Вернёмся к А"))
        f.service.sync(key, messages, profile)
        assertEquals(4, f.service.groups.value[key]?.size, "Fallbacks are immediately available")
        advanceUntilIdle()
        val groups = f.service.groups.value.getValue(key)
        assertEquals(listOf("a1", "b1", "returnA"), groups.map { it.request.messageId })
        assertEquals("a2", groups.first().clarifications.single().messageId)
        assertTrue(f.calls[1].second.last().content.contains("Какой цвет?"), "Short answers need the preceding question")
        assertEquals(listOf(profile, profile, profile, profile), f.calls.map { it.first })
        val resumed = RequestPinService(f.repo, f.gateway, this)
        resumed.sync(key, messages + PinMessage("answer", "Готово", false), profile, reopened = true)
        advanceUntilIdle()
        assertEquals(groups, resumed.groups.value[key])
        assertEquals(4, f.calls.size, "Opening and adding an agent answer must reuse the cache")
    }

    @Test fun failureUsesExcerptAndRetriesOnlyOnReopenOrConnectionChange() = runTest {
        var result = "not json"
        val f = Fixture(this) { result }
        val messages = listOf(input("a", "  Сделай\n\nкороткий   ответ  "))
        f.service.sync(key, messages, profile)
        advanceUntilIdle()
        assertEquals("Сделай короткий ответ", f.service.groups.value[key]?.single()?.request?.summary)
        repeat(3) { f.service.sync(key, messages + PinMessage("agent", "Статус $it", false), profile) }
        advanceUntilIdle()
        assertEquals(1, f.calls.size)
        result = """{"summary":"Краткий ответ","newRequest":false}"""
        f.service.sync(key, messages, profile, reopened = true)
        advanceUntilIdle()
        assertEquals(2, f.calls.size)
        assertTrue(f.repo.load(key).single().newRequest, "The first input always opens a group")
        assertEquals("Краткий ответ", f.service.groups.value[key]?.single()?.request?.summary)
    }

    @Test fun unavailableProfileAndAttachmentOnlyInputsDoNotSendFilesToModel() = runTest {
        val f = Fixture(this) { """{"summary":"Ответ","newRequest":true}""" }
        val messages = listOf(input("a"), PinMessage("file", "", true, attachments = listOf("макет.png")))
        f.service.sync(key, messages, null)
        advanceUntilIdle()
        assertTrue(f.calls.isEmpty())
        f.service.sync(key, messages, profile)
        advanceUntilIdle()
        assertEquals(1, f.calls.size)
        assertEquals("Вложения: макет.png", f.service.groups.value[key]?.single()?.clarifications?.single()?.summary)
        assertFalse(f.calls.single().second.last().content.contains("dataBase64"))
    }

    @Test fun changedInputInvalidatesTheSuffixButNotPreviousSummaries() = runTest {
        val f = Fixture(this) { id -> """{"summary":"$id","newRequest":true}""" }
        val messages = listOf(input("a"), input("b"), input("c"))
        f.service.sync(key, messages, profile)
        advanceUntilIdle()
        f.service.sync(key, listOf(messages[0], input("b", "Изменено"), messages[2]), profile)
        advanceUntilIdle()
        assertEquals(5, f.calls.size)
        assertEquals("Изменено", f.repo.load(key)[1].source.text)
        f.service.sync(key, listOf(messages[0]), profile)
        advanceUntilIdle()
        assertEquals(1, f.repo.load(key).size, "Deleted messages cannot remain pinned")
    }

    @Test fun deletionDiscardsEvenAnUncooperativeLateModelResult() = runTest {
        val reply = CompletableDeferred<String>()
        val f = Fixture(this) { withContext(NonCancellable) { reply.await() } }
        f.service.sync(key, listOf(input("a")), profile)
        runCurrent()
        assertEquals(1, f.calls.size)
        f.service.remove(key)
        f.service.sync(key, listOf(input("a")), profile) // An in-flight abort can publish stale UI state.
        reply.complete("""{"summary":"Поздний результат","newRequest":true}""")
        advanceUntilIdle()
        assertNull(f.service.groups.value[key])
        assertTrue(f.repo.load(key).isEmpty())
    }

    @Test fun concurrentChatsDoNotShareGroupsAndEditingInFlightDropsStaleSummary() = runTest {
        val reply = CompletableDeferred<String>()
        var first = true
        val f = Fixture(this) { id ->
            if (first) { first = false; reply.await() }
            else """{"summary":"Новый $id","newRequest":true}"""
        }
        val coding = PinConversation("chat", "project")
        f.service.sync(key, listOf(input("a", "Старый текст")), profile)
        runCurrent()
        f.service.sync(coding, listOf(input("b")), profile)
        f.service.sync(key, listOf(input("a", "Новый текст")), profile)
        reply.complete("""{"summary":"Устаревший","newRequest":true}""")
        advanceUntilIdle()
        assertEquals("Новый a", f.service.groups.value[key]?.single()?.request?.summary)
        assertEquals("Новый b", f.service.groups.value[coding]?.single()?.request?.summary)
        f.service.remove(key)
        assertEquals(1, f.repo.load(coding).size)
        f.service.clear()
        assertTrue(f.repo.load(coding).isEmpty())
    }

    @Test fun orchestrationAdaptersIgnoreOutgoingCopiesAndStatusOnlyUpdates() {
        val address = SessionAddress("orchestrator", "Большой план", "Оркестратор 1")
        val route = MessageRoute(address, address.copy(sessionId = "worker"), deliveryId = "delivery")
        val task = CodingMessage("a", CodingRole.USER, "Продолжить этап", createdAt = 1, route = route,
            pendingDelivery = true, deliveryId = "delivery")
        val original = listOf(task, task.copy(id = "replayed"), task.copy(id = "outgoing", role = CodingRole.AGENT))
        assertEquals(1, original.pinMessages().count { it.input })
        assertEquals("Оркестратор 1", original.pinMessages().first { it.input }.author)
        assertFalse(original.pinMessages().last().input, "Routed questions remain context, not pins")
        assertEquals(listOf(task).pinMessages(), listOf(task.copy(pendingDelivery = false, inputStatus = OrchestrationInputStatus.DONE)).pinMessages())
    }
}
