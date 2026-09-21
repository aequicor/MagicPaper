package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.backend.PiProviderTurnRequest
import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.json.*
import kotlin.test.*

class PiProviderProtocolTest {
    private val request = PiProviderTurnRequest(
        LlmProfile("subscription", "Subscription", provider = ProviderType.OPENAI_SUBSCRIPTION, modelId = "gpt-5.4"),
        listOf(LlmMessage(LlmChatRole.SYSTEM, "System"), LlmMessage(LlmChatRole.USER, "Question")),
        listOf(LlmToolDefinition("search", "Search", buildJsonObject { put("type", "object") })),
        emptyList(), "node", "pi-ai/dist", "provider-turn.mjs", "fixture-token")

    private fun assistant(stop: String = "toolUse") = buildJsonObject {
        put("role", "assistant"); put("api", "openai-codex-responses"); put("provider", "openai-codex")
        put("model", "gpt-5.4"); put("stopReason", stop); put("timestamp", 42)
        putJsonArray("content") {
            add(buildJsonObject { put("type", "thinking"); put("thinking", ""); put("thinkingSignature", "opaque-signed-content") })
            add(buildJsonObject {
                put("type", "toolCall"); put("id", "call_123|fc_456"); put("name", "search")
                putJsonObject("arguments") { put("query", "question") }
            })
        }
    }

    @Test fun requestDiagnosticsExcludeTokenAndConversation() {
        assertFalse(request.toString().contains("fixture-token"))
        assertFalse(request.toString().contains("Question"))
        assertFalse(request.toString().contains("Search"))
    }

    @Test fun continuationPreservesOpaqueAssistantAndPairsNativeToolIds() {
        val assistant = assistant()
        val turn = PiProviderProtocol.result(assistant, setOf("search"))
        assertEquals("call_123|fc_456", turn.calls.single().id)
        val exchange = LlmToolExchange(turn, listOf(LlmToolResult(turn.calls.single().id, "search", JsonPrimitive("Result"))))
        val payload = PiProviderProtocol.request(request.copy(exchanges = listOf(exchange)))
        assertEquals("openai-codex", payload["model"]!!.jsonObject["provider"]!!.jsonPrimitive.content)
        val messages = payload["context"]!!.jsonObject["messages"]!!.jsonArray
        assertEquals(assistant, messages[1])
        assertEquals("call_123|fc_456", messages[2].jsonObject["toolCallId"]!!.jsonPrimitive.content)
        assertEquals("Result", messages[2].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun rejectsIncompleteCallsBeforeCommonExecutorCanAct() {
        assertFails { PiProviderProtocol.result(assistant("length"), setOf("search")) }
        assertFails { PiProviderProtocol.result(assistant(), setOf("another_tool")) }
        val invalid = JsonObject(assistant() + ("content" to buildJsonArray {
            add(buildJsonObject { put("type", "toolCall"); put("id", "call"); put("name", "search"); put("arguments", "{broken") })
        }))
        assertFails { PiProviderProtocol.result(invalid, setOf("search")) }
        val duplicate = JsonObject(assistant() + ("content" to buildJsonArray {
            add(assistant()["content"]!!.jsonArray[1]); add(assistant()["content"]!!.jsonArray[1])
        }))
        assertFails { PiProviderProtocol.result(duplicate, setOf("search")) }
    }

    @Test fun rejectsChangedOrUnpairedContinuation() {
        val turn = PiProviderProtocol.result(assistant(), setOf("search"))
        fun map(candidate: LlmToolTurn = turn, results: List<LlmToolResult> = emptyList()) =
            PiProviderProtocol.request(request.copy(exchanges = listOf(LlmToolExchange(candidate, results))))
        assertFails { map() }
        assertFails { map(results = listOf(LlmToolResult("wrong-id", "search", JsonNull))) }
        assertFails { map(results = listOf(LlmToolResult(turn.calls.single().id, "wrong-tool", JsonNull))) }
        assertFails { map(turn.copy(provider = ProviderType.ANTHROPIC)) }
        assertFails { map(turn.copy(calls = listOf(turn.calls.single().copy(id = "changed")))) }
    }
}
