package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.*
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class CodexNativeModelLaunchTest {
    private val subscription = LlmProfile("profile", "fixture", provider = ProviderType.OPENAI_SUBSCRIPTION, modelId = "profile-model")
    private val session = CodingSession("session", "project", "", 0, engine = CodingEngine.CODEX)
    private fun pick(level: String?, provider: String = "openai", engine: CodingEngine = CodingEngine.CODEX) =
        session.copy(codingModel = CodingModelSelection(engine, provider, "gpt-6-astra", level))

    @Test fun withoutANativeChoiceTheProfileDecidesAsBefore() {
        val launch = codexLaunchModel(session, subscription, direct = true)
        assertEquals("profile-model", launch.modelId)
        assertEquals(subscription.resolveEffort(ModelDefaults.capability(subscription)).level?.wire, launch.effort)
    }

    @Test fun nativeChoiceReplacesTheProfileModelAndKeepsTheLevelVerbatim() {
        // The app ladder folds `ultra` into `max`; the engine's own vocabulary must reach Codex unchanged.
        assertEquals(CodexLaunchModel("gpt-6-astra", "ultra"), codexLaunchModel(pick("ultra"), subscription, direct = true))
    }

    @Test fun defaultLevelSendsNoEffortSoCodexAppliesItsOwnDefault() {
        val withProfileEffort = subscription.copy(effort = EffortSelection.of(ReasoningEffort.HIGH))
        assertNull(codexLaunchModel(pick(null), withProfileEffort, direct = true).effort)
    }

    @Test fun nativeChoiceOfAnotherEngineOrProviderIsRefusedNotReinterpreted() {
        assertFailsWith<IllegalArgumentException> { codexLaunchModel(pick("low", engine = CodingEngine.PI), subscription, direct = true) }
        assertFailsWith<IllegalArgumentException> { codexLaunchModel(pick("low", provider = "qwen-token-plan"), subscription, direct = true) }
    }

    @Test fun nativeChoiceNeverRunsBehindAForeignResponsesProxy() {
        assertFailsWith<IllegalArgumentException> { codexLaunchModel(pick("low"), subscription, direct = false) }
    }

    @Test fun agentHandsTheNativeChoiceToTheEngineRunRequest() = runBlocking {
        val home = Files.createTempDirectory("codex-native-model")
        val seen = mutableListOf<CodexRunRequest>()
        val delegates = mutableListOf<CodexNativeClient>()
        val agent = CodexBackendAgent(nativeTestEnvironment(home, session.id), CodexNativeAdapter().descriptor) {
            val delegate = nativeTestClient(Json, home).also { delegates += it }
            object : CodexClient by delegate {
                override fun runCoding(session: CodingSession, request: CodexRunRequest, refreshResume: Boolean) =
                    flowOf<CodingEvent>().also { seen += request }
                override suspend fun shutdownCoding() = Unit
            }
        }
        try {
            val request = NativeAgentRequest("request", pick("ultra"), home.toString(), "prompt", "", null, subscription,
                JsonObject(emptyMap()), emptyList(), CodingInteractionMode.CODE,
                NativeAgentTools(emptyList(), emptyMap(), emptyMap(), emptySet(), JsonObject(emptyMap())))
            agent.run(request).collect { }
            val payload = seen.single()
            assertEquals("gpt-6-astra", payload.modelId)
            assertEquals("ultra", payload.effort)
            assertEquals("openai", payload.modelProvider)
        } finally {
            agent.close()
            delegates.forEach { it.close() }
            home.toFile().deleteRecursively()
        }
    }
}
