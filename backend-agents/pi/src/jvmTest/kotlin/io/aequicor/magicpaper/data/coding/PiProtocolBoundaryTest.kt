package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** What the host reads back from Pi through its adapter: events, usage, model configuration and advice. */
class PiProtocolBoundaryTest {
    private val pi = PiNativeAdapter()

    @Test fun sessionEventStartsTheNativeSession() {
        assertEquals(CodingEvent.SessionStarted("native"), pi.parse("""{"type":"session","id":"native"}"""))
    }

    @Test fun compactionUsesActualAbortAndErrorFields() {
        fun phase(body: String) = (pi.parse(body) as CodingEvent.Compaction).status.phase
        assertEquals(CompactionPhase.STARTED, phase("""{"type":"compaction_start","reason":"threshold"}"""))
        assertEquals(CompactionPhase.CANCELLED, phase("""{"type":"compaction_end","aborted":true,"reason":"overflow"}"""))
        assertEquals(CompactionPhase.FAILED, phase("""{"type":"compaction_end","aborted":false,"errorMessage":"failure"}"""))
        assertEquals(CompactionPhase.COMPLETED, phase("""{"type":"compaction_end","aborted":false}"""))
    }

    @Test fun usageIsPreservedOnErrorsAndCompactionSummary() {
        val failed = pi.parseEvents("""{"type":"message_end","message":{"role":"assistant","timestamp":123,"stopReason":"error","usage":{"input":50,"output":10,"cacheRead":20,"cacheWrite":5,"totalTokens":85}}}""")
        assertEquals(85L, failed.filterIsInstance<CodingEvent.UsageObserved>().single().tokens.totalTokens)
        assertTrue(failed.any { it is CodingEvent.Failed })
        val compact = pi.parseEvents("""{"type":"compaction_end","result":{"tokensBefore":200,"usage":{"input":200,"output":25}},"aborted":false}""")
        assertEquals(225L, compact.filterIsInstance<CodingEvent.UsageObserved>().single().tokens.totalTokens)
        assertTrue(compact.any { it is CodingEvent.Compaction })
    }

    @Test fun blankProviderErrorsHaveAnExplicitFallback() {
        for (message in listOf("", " ", " ")) {
            val event = pi.parse("""{"type":"message_end","message":{"role":"assistant","stopReason":"error","errorMessage":"$message"}}""")
            assertEquals("Модель завершила запрос с ошибкой без описания.", assertIs<CodingEvent.Failed>(event).message)
        }
    }

    private val fact = ProviderModel("m", contextWindow = 200000, maxOutputTokens = 50000,
        defaultParameters = mapOf("temperature" to JsonPrimitive(.9), "top_p" to JsonPrimitive(.8)),
        supportedParameters = setOf("temperature", "top_p", "max_tokens"),
        reasoning = DeclaredReasoning(efforts = setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH)))
    private val source = LlmProfile("p", "Provider", baseUrl = "https://test/v1", modelId = "operational",
        favoriteModels = listOf("m"), modelCatalog = listOf(fact), modelLibraryVersion = 1,
        advanced = AdvancedLlmOptions(temperature = .1, contextLimit = 32000))

    @Test fun registryAdvertisesProviderCapacityWhilePerRequestOptionsCarryTheUserLimit() {
        val custom = ModelVariant("variant:1", "Precise", "m", AdvancedLlmOptions(temperature = .2, topP = .7, maxTokens = 7000))
        val request = source.copy(variants = listOf(custom)).forModel(custom.id, EffortSelection.of(ReasoningEffort.HIGH))
        assertEquals(16384, pi.modelConfiguration(request).maxTokens)
    }

    @Test fun codingUsesTheNativeApiOfEachProvider() {
        for ((provider, api) in listOf(ProviderType.ANTHROPIC to "anthropic-messages", ProviderType.GOOGLE to "google-generative-ai",
            ProviderType.OPENROUTER to "openai-completions")) {
            val request = source.copy(provider = provider).forModel("m", EffortSelection.of(ReasoningEffort.HIGH))
            assertEquals(api, pi.modelConfiguration(request).root["providers"]!!.jsonObject["magicpaper"]!!.jsonObject["api"]!!.jsonPrimitive.content)
        }
    }

    @Test fun subscriptionUsesTheNativeProtocolWithoutPersistingTokens() {
        val profile = LlmProfile("s", "ChatGPT", provider = ProviderType.OPENAI_SUBSCRIPTION, modelId = "gpt-test", apiKey = "must-not-be-copied")
        val config = pi.modelConfiguration(profile).root.toString()
        assertContains(config, "openai-codex-responses")
        assertContains(config, "https://chatgpt.com/backend-api")
        assertFalse(config.contains(profile.apiKey))
    }

    @Test fun truncationAdviceKeepsTheRealReasonForTheRecorder() {
        val profile = LlmProfile(id = "p", name = "Alibaba", baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            provider = ProviderType.OPENAI_COMPATIBLE, modelId = "qwen3.8-flash", advanced = AdvancedLlmOptions(maxTokens = 8192))
        val advice = pi.truncationAdvice(profile, 8192, 8192)
        assertTrue(advice.contains("весь лимит вывода на рассуждение"), advice)
        assertTrue(advice.contains("8192 из 16384"), advice)
    }

    @Test fun catalogFactsRaiseTheEngineContextOnlyWhereTheyAreDeclared() {
        val tokenPlan = "https://token-plan.example.com/compatible-mode/v1"
        val saved = LlmProfile("alibaba", "Alibaba", baseUrl = tokenPlan, modelId = "qwen3.8-max", modelLibraryVersion = 1,
            advanced = AdvancedLlmOptions(contextLimit = 128_000),
            modelCatalog = listOf(ProviderModel("qwen3.8-max"), ProviderModel("qwen3.7-plus")))
        val declared = ModelLimitCatalog { _, ids -> ids.filter { it == "qwen3.8-max" }.associateWith { CatalogModelLimits(1_000_000, 131_072) } }
        val runtime = saved.withCatalogLimits(declared).forCoding()
        assertEquals(1_000_000, runtime.advanced.safeContextLimit)
        assertEquals(1_000_000, pi.modelConfiguration(runtime).contextWindow)
        assertTrue(pi.modelConfiguration(runtime).root.toString().contains("\"contextWindow\":1000000"))
        // Without declared facts the request stays at the configured limit: no guess by model family.
        assertEquals(128_000, pi.modelConfiguration(saved.forCoding()).contextWindow)
    }
}
