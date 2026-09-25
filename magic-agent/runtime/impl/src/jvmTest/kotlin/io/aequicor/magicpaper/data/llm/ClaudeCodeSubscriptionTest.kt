package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.test.*

/** The chat's transport for the Claude subscription: one plain answer per turn, billed to the subscription. */
class ClaudeCodeSubscriptionTest {
    private class Adapter(val answer: () -> String) : NativeAgentAdapter {
        val requests = mutableListOf<NativeCompletionRequest>()
        override val descriptor = createBackendAgentCatalog().descriptor(CodingEngine.CLAUDE_CODE)
        override val rootPath = "fixture"
        override val approvals: NativeApprovalRequests? = null
        override val history: NativeToolHistory? = null
        override val removal: NativeRemoval? = null
        override val models = NativeModelCatalog {
            listOf(CodingModel("anthropic", "opus", "Opus", 1_000_000, 128_000, listOf("low", "high", "max", "ultracode"), defaultLevel = "high"),
                CodingModel("anthropic", "haiku", "Haiku"))
        }
        override val signIn = NativeSignIn { EngineSignInResult.SignedIn }
        override val signOut = NativeSignOut { EngineSignOutResult.SignedOut }
        override val completion = object : NativeCompletion {
            override val provider = ProviderType.ANTHROPIC_SUBSCRIPTION
            override suspend fun complete(request: NativeCompletionRequest, onActivity: (CodingStep) -> Unit, onUsage: (UsageCallResult) -> Unit): String {
                requests += request
                onActivity(CodingStep(CodingStepKind.TOOL, "Поиск", tool = "WebSearch"))
                onUsage(UsageCallResult(TokenUsage(input = 7, output = 3)))
                return answer()
            }
        }
        override suspend fun status() = NativeInstallationStatus(NativeInstallationPhase.READY, "Ready", signedIn = false)
        override fun prepare() = flowOf(NativeInstallationStatus(NativeInstallationPhase.READY, "Ready"))
        override fun modelProfile(profile: LlmProfile, mode: CodingInteractionMode, speedBoost: Boolean) = profile
        override fun modelConnection(profile: LlmProfile) = NativeModelConnectionKind.DIRECT
        override fun run(request: NativeAgentRequest) = emptyFlow<CodingEvent>()
        override suspend fun reconcile(sessionId: String) = true
        override fun abort(sessionId: String) = Unit
        override fun abortAll() = Unit
        override fun close() = Unit
    }

    private val profile = LlmProfile("p", "Anthropic (подписка Claude Code)", "", provider = ProviderType.ANTHROPIC_SUBSCRIPTION,
        modelId = "opus", effortOverrides = mapOf("opus" to EffortSelection.of(ReasoningEffort.HIGH)))
    private val tool = LlmToolDefinition("web.search", "Search", buildJsonObject { put("type", "object") })
    private val messages = listOf(LlmMessage(LlmChatRole.SYSTEM, "Отвечай кратко"), LlmMessage(LlmChatRole.USER, "Какая погода?"))

    @Test fun aTurnIsOnePlainAnswerOnTheSubscriptionWithoutApplicationTools() = runBlocking {
        val adapter = Adapter { "Солнечно." }
        val turn = ClaudeCodeSubscription(adapter).turn(profile, messages, listOf(tool))
        assertEquals("Солнечно.", turn.text)
        assertTrue(turn.calls.isEmpty())
        assertEquals(ProviderType.ANTHROPIC_SUBSCRIPTION, turn.provider)
        val request = adapter.requests.single()
        assertEquals("opus", request.modelId)
        assertEquals("Отвечай кратко", request.systemInstructions)
        assertEquals(ClaudeCodeSubscription.CHAT_INSTRUCTIONS, request.baseInstructions)
        assertEquals("high", request.effort)
        assertContains(request.input.first().jsonObject["text"]!!.jsonPrimitive.content, "Пользователь: Какая погода?")
    }

    @Test fun selectedUltracodeReachesTheClaudeCommandWithoutBecomingMax() = runBlocking {
        val adapter = Adapter { "Готово." }
        val selected = profile.withEffortFor("opus", EffortSelection.of(ReasoningEffort.ULTRACODE))
        ClaudeCodeSubscription(adapter).complete(selected, messages)
        assertEquals("ultracode", adapter.requests.single().effort)
    }

    @Test fun aContinuationIsRefusedBecauseThisTransportNeverReturnsACall() = runBlocking {
        val exchange = LlmToolExchange(LlmToolTurn(provider = ProviderType.ANTHROPIC), emptyList())
        assertFailsWith<LlmToolProtocolException> { ClaudeCodeSubscription(Adapter { "" }).turn(profile, messages, listOf(tool), listOf(exchange)) }
        Unit
    }

    @Test fun usageReachesTheCallAndActivityIsForwarded() = runBlocking {
        val call = UsageCall()
        val steps = mutableListOf<CodingStep>()
        withContext(call) { ClaudeCodeSubscription(Adapter { "ok" }).completeWithActivity(profile, messages) { steps += it } }
        assertEquals(TokenUsage(input = 7, output = 3), call.result.value.tokens)
        assertEquals(listOf("WebSearch"), steps.map { it.tool })
    }

    @Test fun modelsAreClaudeCodesAliasesWithTheirLevels() = runBlocking {
        val models = ClaudeCodeSubscription(Adapter { "" }).models(profile)
        assertEquals(listOf("haiku", "opus"), models.map { it.id })
        assertFalse(models.single { it.id == "haiku" }.supportsEffort, "A model without levels takes none")
        val opus = assertIs<ReasoningCapability.Controls>(models.single { it.id == "opus" }.reasoning)
        assertEquals(setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX, ReasoningEffort.ULTRACODE), opus.values,
            "The subscription keeps max and ultracode as separate CLI choices")
        assertEquals(ReasoningEffort.HIGH, opus.default, "The model's own effort is the default")
        assertEquals("extra", opus.levelName(ReasoningEffort.XHIGH), "xhigh is named as Claude's picker names it")
        val fact = checkNotNull(models.single { it.id == "opus" }.metadata)
        assertEquals(1_000_000, fact.contextWindow, "The model's own window reaches the profile, not a guessed limit")
        assertEquals(128_000, fact.maxOutputTokens)
    }

    @Test fun signedOutCliReachesTheChatAsARejectionThatNamesSignIn() = runBlocking {
        val failure = assertFailsWith<LlmTransportException> {
            ClaudeCodeSubscription(Adapter { throw NativeCompletionFailure("Claude Code не авторизован.", signedOut = true) }).complete(profile, messages)
        }
        assertEquals(failure, failure.transportRejection())
        assertTrue(failure.confirmedRejection, "No model answered, so the request is safe to repeat after signing in")
        assertTrue(failure.blocksAutomaticRetry, "Only signing in changes the outcome, so no attempt is spent repeating it")
        assertContains(failure.safeReason(), "войдите")
        val other = assertFailsWith<NativeCompletionFailure> {
            ClaudeCodeSubscription(Adapter { throw NativeCompletionFailure("Claude Code вернул пустой ответ.") }).complete(profile, messages)
        }
        assertFalse(other.signedOut)
    }

    @Test fun accountStateSignInAndSignOutComeFromTheCli() = runBlocking {
        val subscription = ClaudeCodeSubscription(Adapter { "" })
        assertEquals(false, subscription.signedIn())
        assertEquals(EngineSignInResult.SignedIn, subscription.signIn())
        assertEquals(EngineSignOutResult.SignedOut, subscription.signOut())
    }
}
