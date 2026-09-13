package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.data.storage.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.test.*

class UsageParsingTest {
    @Test fun piPlaceholderCountersDoNotClaimKnownZeroUsage() {
        assertEquals(TokenUsage(), UsageParsing.pi(Json.parseToJsonElement("""{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"totalTokens":0}""").jsonObject))
    }
    private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject
    @Test fun nativeFormatsNormalizeCacheAndReasoningWithoutDoubleCounting() {
        val openai = UsageParsing.openAi(obj("""{"prompt_tokens":120,"completion_tokens":30,"total_tokens":150,"prompt_tokens_details":{"cached_tokens":100},"completion_tokens_details":{"reasoning_tokens":20}}"""))
        assertEquals(20L, openai.input); assertEquals(100L, openai.cacheRead); assertEquals(150L, openai.totalTokens)
        val anthropic = UsageParsing.anthropic(obj("""{"input_tokens":20,"output_tokens":30,"cache_read_input_tokens":80,"cache_creation_input_tokens":20}"""))
        assertEquals(150L, anthropic.totalTokens); assertEquals(20L, anthropic.cacheWrite)
        val google = UsageParsing.google(obj("""{"promptTokenCount":120,"candidatesTokenCount":10,"thoughtsTokenCount":20,"cachedContentTokenCount":100,"totalTokenCount":150}"""))
        assertEquals(20L, google.input); assertEquals(30L, google.output); assertEquals(20L, google.reasoning)
        assertEquals(150L, google.totalTokens)
        assertNull(UsageParsing.openAi(obj("{}" )).input)
        assertNull(UsageParsing.openAi(obj("""{"prompt_tokens":-2}""")).input)
        assertNull(openai.cacheWrite); assertNull(openai.cachedOutput)
        val router = UsageParsing.openAi(obj("""{"prompt_tokens":120,"completion_tokens":30,"prompt_tokens_details":{"cached_tokens":80,"cache_write_tokens":20}}"""))
        assertEquals(20L, router.input); assertEquals(20L, router.cacheWrite); assertEquals(150L, router.totalTokens)
    }

    @Test fun routingCapturesUsageEvenWhenResponseHasNoUsableText() = runTest {
        val client = HttpClient(MockEngine { respond("""{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":12,"cost":0.003}}""", headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        val ledger = UsageLedger(JsonUsageRepository(InMemoryKeyValueStore(), Json))
        val gateway = RoutingLlmGateway(mapOf(ProviderType.OPENAI_COMPATIBLE to OpenAiCompatibleGateway(client, Json)), ledger)
        try {
            val failure = assertFailsWith<IllegalStateException> { withContext(Dispatchers.Default) { gateway.complete(LlmProfile(id = "p", name = "test", baseUrl = "https://fixture.invalid", modelId = "fixture"), emptyList()) } }
            assertTrue(failure.message.orEmpty().contains("Ошибка ответа модели"), failure.message)
            val record = ledger.state.value.records.single()
            assertEquals(100L, record.tokens.input); assertEquals(12L, record.tokens.output)
            assertEquals(.003, record.cost?.amount); assertFalse(record.completed)
        } finally { client.close() }
    }

    @Test fun catalogPricesAreOptionalProviderFacts() {
        val models = parseProviderModels(Json, """{"data":[{"id":"m","pricing":{"prompt":"0.000001","completion":"0.000002","input_cache_read":"0.0000001"}},{"id":"unknown"}]}""", ProviderType.OPENROUTER)
        assertEquals(.000001, models.first().metadata?.pricing?.input)
        assertNull(models.last().metadata?.pricing)
    }
}
