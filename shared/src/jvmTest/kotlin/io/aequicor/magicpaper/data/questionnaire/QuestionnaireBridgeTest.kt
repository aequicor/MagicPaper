package io.aequicor.magicpaper.data.questionnaire

import io.aequicor.magicpaper.domain.*
import java.net.URI
import java.net.HttpURLConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import kotlin.test.*

class QuestionnaireBridgeTest {
    private val session = CodingSession("s", "p", "Сессия", 0)
    private val args = """{"questions":[{"id":"q","title":"Формат?","kind":"SINGLE","options":[{"id":"pdf","label":"PDF"}]}]}"""
    private fun call(url: String, token: String, id: String = "1"): JsonObject {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"; connection.doOutput = true; connection.readTimeout = 8000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write("""{"jsonrpc":"2.0","id":"$id","method":"tools/call","params":{"name":"questionnaire","arguments":$args}}""".toByteArray()) }
        return try { Json.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject } finally { connection.disconnect() }
    }

    @Test fun liveMcpRoundTripWaitsForConfirmedAnswersAndReplayDoesNotAskAgain() = runBlocking {
        val registry = RuntimeQuestionnaires()
        QuestionnaireBridge(registry, session).use { bridge ->
            val response = async(Dispatchers.IO) { call(bridge.url, bridge.token) }
            val question = withTimeout(5000) { registry.requests.first { it.isNotEmpty() }.single() }
            assertFalse(response.isCompleted)
            assertEquals(session.id, question.sessionId)
            val answers = listOf(PlanningAnswer("q", listOf("pdf"), "С комментариями"))
            registry.respond(question.id, answers)
            val result = response.await()
            assertContains(result.toString(), "С комментариями")
            assertEquals(result, withContext(Dispatchers.IO) { call(bridge.url, bridge.token) })
            assertTrue(registry.requests.value.isEmpty())
        }
    }

    @Test fun closingConnectionInvalidatesQuestionAndCannotGrantAnyPermissions() = runBlocking {
        val registry = RuntimeQuestionnaires()
        val bridge = QuestionnaireBridge(registry, session)
        val response = async(Dispatchers.IO) { runCatching { call(bridge.url, bridge.token) } }
        withTimeout(5000) { registry.requests.first { it.isNotEmpty() } }
        bridge.close()
        assertTrue(registry.requests.value.isEmpty(), "Close must join questionnaire cancellation before returning")
        assertEquals(RuntimeQuestionnaireStatus.CANCELLED, registry.history.value.single().status)
        withTimeout(5000) { registry.requests.first { it.isEmpty() } }
        response.await()
        val questions = QuestionnaireTool.decode(Json.parseToJsonElement(args).jsonObject)
        assertTrue(questions.single().allowCustomInput && questions.single().canSkip)
    }

    @Test fun oldEndpointsAreReplacedOnEveryCodexResumeAndNormalTimeoutIsNotUsed() = runBlocking {
        val registry = RuntimeQuestionnaires()
        QuestionnaireBridge(registry, session).use { first -> QuestionnaireBridge(registry, session).use { second ->
            val config = second.codexConfig(first.codexConfig(buildJsonObject {}))
            val server = config["mcp_servers.magicpaper_questionnaire"]!!.jsonObject
            assertEquals(JsonPrimitive(second.url), server["url"])
            assertFalse(server.toString().contains(first.token))
            assertTrue(server["tool_timeout_sec"]!!.jsonPrimitive.long > 86_400)
        } }
    }
}
