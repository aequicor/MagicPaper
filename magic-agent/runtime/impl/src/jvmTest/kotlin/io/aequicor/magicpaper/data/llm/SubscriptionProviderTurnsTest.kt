package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.*
import kotlin.test.*

class SubscriptionProviderTurnsTest {
    private class Installation(var phase: NativeInstallationPhase = NativeInstallationPhase.READY) : PiInstallation {
        override val cliPath = "unused-agent-cli"
        override fun aiDirectory() = "library"
        override suspend fun status() = NativeInstallationStatus(phase, "private installer detail")
        override fun ensureReady() = flowOf(NativeInstallationStatus(phase, "private installer detail"))
        override suspend fun uninstall() = error("Not part of a provider call")
        override suspend fun node() = "node"
        override fun prepareBundledTools() = error("Not part of a provider call")
        override fun toolsNotice() = ""
        override fun ensureFuzzySafety() = error("Not part of a provider call")
        override fun bashPath(): String? = null
        override fun homeDefaults(directory: String) = error("Not part of a provider call")
        override fun environment(nodePath: String, home: String) = emptyMap<String, String>()
    }
    private class Provider(override val installation: Installation = Installation()) : NativeProviderLibrary {
        override suspend fun shutdown() = close()
        override suspend fun prepareForReset() = Unit
        override suspend fun resumeAfterReset() = Unit
        override fun prepare() = installation.ensureReady()
        override suspend fun bridge(profile: LlmProfile, parameters: JsonObject): NativeProviderBridge = error("Not part of provider turn")
        data class Request(val messages: List<LlmMessage>, val tools: List<LlmToolDefinition>,
            val exchanges: List<LlmToolExchange>, val accessToken: String)

        val requests = mutableListOf<Request>()
        var closed = false
        val answer = LlmToolTurn("", listOf(LlmToolCall("call|fc_item", "search", JsonObject(emptyMap()))),
            buildJsonObject { put("opaque", "continuation") }, ProviderType.OPENAI_SUBSCRIPTION)
        override suspend fun turn(profile: LlmProfile, messages: List<LlmMessage>, tools: List<LlmToolDefinition>,
            exchanges: List<LlmToolExchange>, accessToken: String, onUsage: (UsageCallResult) -> Unit): LlmToolTurn {
            requests += Request(messages, tools, exchanges, accessToken)
            onUsage(UsageCallResult(TokenUsage(input = 7, output = 2), requests = 1))
            return answer
        }
        override fun close() { closed = true }
    }
    private val profile = LlmProfile("subscription", "Subscription", provider = ProviderType.OPENAI_SUBSCRIPTION, modelId = "gpt-5.4")

    @Test fun forwardsCommonToolsAndOpaqueContinuationWithOneUsagePerTurn() = runBlocking<Unit> {
        val provider = Provider()
        val usage = UsageCall()
        var refreshes = 0
        val messages = listOf(LlmMessage(LlmChatRole.USER, "Question"))
        val tools = listOf(LlmToolDefinition("search", "Search", JsonObject(emptyMap())))
        SubscriptionProviderTurns(provider) { refreshes++; "fixture-access" }.let { transport ->
            val turn = withContext(usage) { transport.turn(profile, messages, tools, emptyList()) }
            assertEquals(provider.answer, turn)
            assertEquals(1L, usage.result.value.requests)
            assertEquals(9L, usage.result.value.tokens.totalTokens)
            val exchanges = listOf(LlmToolExchange(turn, listOf(LlmToolResult("call|fc_item", "search", JsonPrimitive("Result")))))
            transport.turn(profile, messages, tools, exchanges)
            assertEquals(exchanges, provider.requests.last().exchanges)
            assertEquals(tools, provider.requests.last().tools)
            assertEquals(messages, provider.requests.last().messages)
            assertEquals("fixture-access", provider.requests.last().accessToken)
        }
        assertEquals(2, refreshes)
        assertFalse(provider.closed, "A single model call cannot close the shared native provider owner")
    }

    @Test fun installationFailureIsVisibleBeforeTokenRefreshOrRequest() = runBlocking<Unit> {
        val provider = Provider(Installation(NativeInstallationPhase.ERROR))
        SubscriptionProviderTurns(provider) { fail("Token must not be read before dependencies are ready") }.let { transport ->
            val failure = assertFailsWith<IllegalStateException> { transport.turn(profile, emptyList(), emptyList(), emptyList()) }
            assertContains(failure.message.orEmpty(), "настройки")
            assertFalse(failure.message.orEmpty().contains("private installer detail"))
            assertTrue(provider.requests.isEmpty())
        }
    }

    @Test fun tokenRefreshCancellationNeverBecomesProviderRequest() = runBlocking<Unit> {
        val provider = Provider()
        SubscriptionProviderTurns(provider) { throw CancellationException("cancel refresh") }.let { transport ->
            assertFailsWith<CancellationException> { transport.turn(profile, emptyList(), emptyList(), emptyList()) }
            assertTrue(provider.requests.isEmpty())
        }
    }
}
