package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

/** Real transports against scripted HTTP: no credentials or external requests. */
@OptIn(ExperimentalCoroutinesApi::class)
class LlmToolTurnTest {
    private val schema = obj("""{"type":"object","properties":{"query":{"type":"string"}},"required":["query"],"additionalProperties":false}""")
    private val tools = listOf(LlmToolDefinition("lookup", "Read a reference", schema))
    private val messages = listOf(LlmMessage(LlmChatRole.SYSTEM, "Use references"), LlmMessage(LlmChatRole.USER, "Find two"))
    private val providers = listOf(ProviderType.OPENAI_COMPATIBLE, ProviderType.OPENROUTER, ProviderType.ANTHROPIC, ProviderType.GOOGLE)

    @Test fun openAiParallelCallsRoundTripOpaqueReasoningAndOrderedResults() = runTest {
        for (provider in listOf(ProviderType.OPENAI_COMPATIBLE, ProviderType.OPENROUTER)) {
            val requests = mutableListOf<HttpRequestData>()
            client(requests, openAiCalls, openAiFinal).use { client ->
                val gateway = gateway(provider, client)
                val first = gateway.turn(profile(provider), messages, tools)
                assertEquals("Checking", first.text)
                assertEquals(listOf("a", "b"), first.calls.map { it.id })
                assertEquals(provider, first.provider)
                val exchange = exchange(first)
                val second = gateway.turn(profile(provider), messages, tools, listOf(exchange))
                assertEquals("Done", second.text)
                assertTrue(second.calls.isEmpty())
                val payload = requests.last().payload()
                val history = payload.getValue("messages").jsonArray
                assertEquals(first.continuation, history[2])
                assertEquals("hidden", history[2].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
                assertEquals("sig", history[2].jsonObject["reasoning_details"]?.jsonArray?.first()?.jsonObject?.get("signature")?.jsonPrimitive?.content)
                assertEquals(listOf("a", "b"), history.drop(3).map { it.jsonObject.getValue("tool_call_id").jsonPrimitive.content })
                assertEquals("first result", history[3].jsonObject.getValue("content").jsonPrimitive.content)
                assertTrue(obj(history[4].jsonObject.getValue("content").jsonPrimitive.content).getValue("isError").jsonPrimitive.boolean)
                assertEquals(schema, payload.getValue("tools").jsonArray.single().jsonObject.getValue("function").jsonObject["parameters"])
                assertEquals("Bearer fixture-key", requests.first().headers["Authorization"])
                assertTransportControls(requests)
                assertEquals(exchange, Json.decodeFromString<LlmToolExchange>(Json.encodeToString(exchange)))
            }
        }
    }

    @Test fun anthropicReturnsThinkingAndAllToolResultsInOneUserMessage() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        client(requests, anthropicCalls, anthropicFinal).use { client ->
            val gateway = gateway(ProviderType.ANTHROPIC, client)
            val first = gateway.turn(profile(ProviderType.ANTHROPIC), messages, tools)
            assertEquals("Checking", first.text)
            val result = gateway.turn(profile(ProviderType.ANTHROPIC), messages, tools, listOf(exchange(first)))
            assertEquals("Done", result.text)
            val payload = requests.last().payload()
            val history = payload.getValue("messages").jsonArray
            assertEquals(first.continuation, history[1])
            val blocks = history[1].jsonObject.getValue("content").jsonArray
            assertEquals("signature", blocks[0].jsonObject.getValue("signature").jsonPrimitive.content)
            assertEquals("encrypted", blocks[1].jsonObject.getValue("data").jsonPrimitive.content)
            val results = history[2].jsonObject.getValue("content").jsonArray
            assertEquals(listOf("a", "b"), results.map { it.jsonObject.getValue("tool_use_id").jsonPrimitive.content })
            assertEquals("first result", results.first().jsonObject.getValue("content").jsonPrimitive.content)
            assertTrue(results.last().jsonObject.getValue("is_error").jsonPrimitive.boolean)
            assertEquals(schema, payload.getValue("tools").jsonArray.single().jsonObject["input_schema"])
            assertEquals("Use references", payload.getValue("system").jsonPrimitive.content)
            assertEquals("fixture-key", requests.first().headers["x-api-key"])
            assertEquals("2023-06-01", requests.first().headers["anthropic-version"])
            assertTransportControls(requests)
        }
    }

    @Test fun googlePreservesThoughtSignaturesAndOnlyEchoesProviderIds() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        client(requests, googleCalls, googleCalls, googleFinal).use { client ->
            val gateway = gateway(ProviderType.GOOGLE, client)
            val first = gateway.turn(profile(ProviderType.GOOGLE), messages, tools)
            assertEquals("Checking", first.text)
            assertEquals(listOf("a", "google-0-1"), first.calls.map { it.id })
            val firstExchange = exchange(first)
            val second = gateway.turn(profile(ProviderType.GOOGLE), messages, tools, listOf(firstExchange))
            assertEquals(listOf("a", "google-1-1"), second.calls.map { it.id })
            val result = gateway.turn(profile(ProviderType.GOOGLE), messages, tools, listOf(firstExchange, exchange(second)))
            assertEquals("Done", result.text)
            val payload = requests.last().payload()
            val history = payload.getValue("contents").jsonArray
            assertEquals(first.continuation, history[1])
            assertEquals(second.continuation, history[3])
            val parts = history[1].jsonObject.getValue("parts").jsonArray
            assertEquals("signature", parts[2].jsonObject.getValue("thoughtSignature").jsonPrimitive.content)
            val results = history[2].jsonObject.getValue("parts").jsonArray.map { it.jsonObject.getValue("functionResponse").jsonObject }
            assertEquals("a", results.first().getValue("id").jsonPrimitive.content)
            assertFalse("id" in results.last())
            assertEquals("first result", results.first().getValue("response").jsonObject.getValue("result").jsonPrimitive.content)
            assertEquals(JsonPrimitive("failed"), results.last().getValue("response").jsonObject["error"])
            val definition = payload.getValue("tools").jsonArray.single().jsonObject.getValue("functionDeclarations").jsonArray.single().jsonObject
            assertEquals(schema, definition["parametersJsonSchema"])
            assertEquals("fixture-key", requests.first().headers["x-goog-api-key"])
            assertTrue(requests.first().url.encodedPath.endsWith("/models/custom-model:generateContent"))
            assertTransportControls(requests)
        }
    }

    @Test fun malformedCallsNeverBecomeSuccessfulTextAnswers() = runTest {
        val malformed = listOf(
            ProviderType.OPENAI_COMPATIBLE to openAiCalls.replace("\"id\":\"b\"", "\"id\":\"a\""),
            ProviderType.OPENAI_COMPATIBLE to openAiCalls.replace("\"id\":\"a\",", ""),
            ProviderType.OPENAI_COMPATIBLE to openAiCalls.replace("\"type\":\"function\"", "\"type\":\"custom\""),
            ProviderType.OPENAI_COMPATIBLE to openAiCalls.replace("{\\\"query\\\":\\\"one\\\"}", "[1]"),
            ProviderType.OPENAI_COMPATIBLE to openAiCalls.replace("\"finish_reason\":\"tool_calls\"", "\"finish_reason\":\"length\""),
            ProviderType.OPENAI_COMPATIBLE to """{"choices":[{"finish_reason":"tool_calls","message":{"content":"Checking"}}]}""",
            ProviderType.ANTHROPIC to anthropicCalls.replace("\"id\":\"b\"", "\"id\":\"a\""),
            ProviderType.ANTHROPIC to anthropicCalls.replace("\"input\":{\"query\":\"one\"}", "\"input\":null"),
            ProviderType.ANTHROPIC to anthropicCalls.replace("\"type\":\"tool_use\"", "\"type\":\"server_tool_use\""),
            ProviderType.ANTHROPIC to anthropicCalls.replace("\"stop_reason\":\"tool_use\"", "\"stop_reason\":\"max_tokens\""),
            ProviderType.GOOGLE to googleCalls.replace("\"name\":\"lookup\"", "\"name\":\"\""),
            ProviderType.GOOGLE to googleCalls.replace("\"args\":{\"query\":\"one\"}", "\"args\":[]"),
            ProviderType.GOOGLE to googleCalls.replace("\"id\":\"a\"", "\"id\":\"\""),
            ProviderType.GOOGLE to googleCalls.replace("\"finishReason\":\"STOP\"", "\"finishReason\":\"MAX_TOKENS\""),
        )
        for ((provider, response) in malformed) {
            val requests = mutableListOf<HttpRequestData>()
            client(requests, response).use { client ->
                val failure = assertFailsWith<LlmToolProtocolException> { gateway(provider, client).turn(profile(provider), messages, tools) }
                assertFalse(failure.message.orEmpty().contains("one"))
                assertEquals(1, requests.size)
            }
        }
    }

    @Test fun invalidContinuationIsRejectedBeforeSendingAnotherRequest() = runTest {
        for (provider in providers) {
            val requests = mutableListOf<HttpRequestData>()
            client(requests, calls(provider)).use { client ->
                val gateway = gateway(provider, client)
                val turn = gateway.turn(profile(provider), messages, tools)
                val valid = exchange(turn)
                val invalid = listOf(
                    valid.copy(turn = turn.copy(provider = ProviderType.OPENAI_SUBSCRIPTION)),
                    valid.copy(results = valid.results.drop(1)),
                    valid.copy(results = listOf(valid.results.first(), valid.results.first())),
                    valid.copy(results = valid.results.map { it.copy(name = "different") }),
                    valid.copy(turn = turn.copy(calls = turn.calls.map { it.copy(arguments = obj("""{"query":"changed"}""")) })),
                )
                invalid.forEach { exchange ->
                    assertFailsWith<IllegalArgumentException> { gateway.turn(profile(provider), messages, tools, listOf(exchange)) }
                }
                assertEquals(1, requests.size)
            }
        }
    }

    @Test fun routingMeasuresEachTurnOnceAndResolvesSelectedVariant() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val usageStore = io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore()
        val ledger = DefaultUsageLedger(io.aequicor.magicpaper.data.storage.JsonUsageRepository(usageStore, Json),
            io.aequicor.magicpaper.data.storage.InMemoryEventJournal(), usageStore, Json)
        client(requests, openAiCalls, openAiFinal).use { client ->
            val gateway = RoutingLlmGateway(mapOf(ProviderType.OPENAI_COMPATIBLE to OpenAiCompatibleGateway(client, Json)), ledger)
            val profile = profile(ProviderType.OPENAI_COMPATIBLE).let { it.copy(modelId = "variant:chosen",
                variants = listOf(ModelVariant("variant:chosen", "Chosen", "wire-model", it.advanced))) }
            val first = gateway.turn(profile, messages, tools)
            gateway.turn(profile, messages, tools, listOf(exchange(first)))
            assertEquals(2, requests.size)
            assertEquals(listOf("wire-model", "wire-model"), requests.map { it.payload().getValue("model").jsonPrimitive.content })
            assertEquals(2, ledger.state.value.records.size)
            assertTrue(ledger.state.value.records.all { it.completed && it.model == "wire-model" && it.tokens.totalTokens == 12L })
        }
    }

    @Test fun defaultTurnRejectsToolsInsteadOfSilentlyDroppingThem() = runTest {
        var completions = 0
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String { completions++; return "Done" }
        }
        val profile = profile(ProviderType.OPENAI_COMPATIBLE)
        assertEquals("Done", gateway.turn(profile, messages, emptyList()).text)
        assertFailsWith<UnsupportedOperationException> { gateway.turn(profile, messages, tools) }
        assertFailsWith<UnsupportedOperationException> { gateway.turn(profile, messages, emptyList(), listOf(LlmToolExchange(LlmToolTurn(), emptyList()))) }
        assertEquals(1, completions)
    }

    @Test fun cancellationRemainsCancellationForEveryProvider() = runTest {
        for (provider in providers) {
            HttpClient(MockEngine(MockEngineConfig().apply {
                dispatcher = UnconfinedTestDispatcher(testScheduler)
                addHandler { throw CancellationException("cancelled") }
            })).use { client ->
                assertFailsWith<CancellationException> { gateway(provider, client).turn(profile(provider), messages, tools) }
            }
        }
    }

    private fun profile(provider: ProviderType) = LlmProfile("profile", "Fixture", "https://provider.invalid/v1", "fixture-key",
        provider = provider, modelId = "custom-model", advanced = AdvancedLlmOptions(timeoutSeconds = 73))

    private fun gateway(provider: ProviderType, client: HttpClient): LlmGateway = when (provider) {
        ProviderType.ANTHROPIC -> AnthropicGateway(client, Json)
        ProviderType.GOOGLE -> GoogleGateway(client, Json)
        else -> OpenAiCompatibleGateway(client, Json)
    }

    private fun TestScope.client(requests: MutableList<HttpRequestData>, vararg responses: String) =
        HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = UnconfinedTestDispatcher(testScheduler)
            responses.forEach { response -> addHandler { request -> requests += request; respond(response) } }
        }))

    private fun exchange(turn: LlmToolTurn) = LlmToolExchange(turn, turn.calls.mapIndexed { index, call ->
        LlmToolResult(call.id, call.name, JsonPrimitive(if (index == 0) "first result" else "failed"), isError = index != 0)
    }.reversed())

    private fun assertTransportControls(requests: List<HttpRequestData>) {
        assertEquals(listOf(73_000L).toSet(), requests.map { it.getCapabilityOrNull<HttpTimeoutConfig>(HttpTimeoutCapability)?.requestTimeoutMillis }.toSet())
        assertTrue(requests.all { it.body.contentType?.toString() == "application/json" })
    }

    private suspend fun HttpRequestData.payload() = obj(body.toByteArray().decodeToString())
    private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun calls(provider: ProviderType) = when (provider) {
        ProviderType.ANTHROPIC -> anthropicCalls
        ProviderType.GOOGLE -> googleCalls
        else -> openAiCalls
    }

    private val openAiCalls = """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"Checking","reasoning_content":"hidden","reasoning_details":[{"signature":"sig"}],"tool_calls":[{"id":"a","type":"function","function":{"name":"lookup","arguments":"{\"query\":\"one\"}"}},{"id":"b","type":"function","function":{"name":"lookup","arguments":"{\"query\":\"two\"}"}}]}}],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}"""
    private val openAiFinal = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Done"}}],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}"""
    private val anthropicCalls = """{"stop_reason":"tool_use","content":[{"type":"thinking","thinking":"hidden","signature":"signature"},{"type":"redacted_thinking","data":"encrypted"},{"type":"text","text":"Checking"},{"type":"tool_use","id":"a","name":"lookup","input":{"query":"one"}},{"type":"tool_use","id":"b","name":"lookup","input":{"query":"two"}}]}"""
    private val anthropicFinal = """{"stop_reason":"end_turn","content":[{"type":"text","text":"Done"}]}"""
    private val googleCalls = """{"candidates":[{"finishReason":"STOP","content":{"role":"model","parts":[{"thought":true,"text":"hidden","thoughtSignature":"thought"},{"text":"Checking"},{"functionCall":{"id":"a","name":"lookup","args":{"query":"one"}},"thoughtSignature":"signature"},{"functionCall":{"name":"lookup","args":{"query":"two"}}}]}}]}"""
    private val googleFinal = """{"candidates":[{"finishReason":"STOP","content":{"role":"model","parts":[{"text":"Done"}]}}]}"""
}
