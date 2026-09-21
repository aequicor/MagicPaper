package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.*
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals

/** Model-cache tests never start an external process. Unexpected effects fail the test. */
internal fun nativeTestClient(json: Json, home: Path,
    diagnostics: NativeDiagnostics = NativeDiagnostics { _, _, cause, _ -> throw AssertionError("Unexpected native failure", cause) },
) = CodexNativeClient(json, home, null,
    object : NativeProcessRecovery {
        override fun record(id: String, process: Process, attachLifetime: Boolean) = error("Unexpected process start")
        override fun clear(id: String) = error("Unexpected process receipt cleanup")
        override fun belongsTo(id: String, process: Process?) = false
        override fun reconcile(id: String) = error("Unexpected process reconciliation")
    }, NativeAuthTokens { error("Unexpected token read") },
    object : NativeQuestionnaires {
        override suspend fun ask(request: UserInteractionRequest): List<PlanningAnswer> = error("Unexpected questionnaire")
        override suspend fun beginDelivery(requestId: String): String = error("Unexpected delivery")
        override suspend fun finishDelivery(requestId: String, attemptId: String, outcome: QuestionnaireDeliveryOutcome): Unit = error("Unexpected delivery")
    }, diagnostics,
    NativeToolPresentationResolver { server, tool, arguments -> NativeToolPresentation("$server:$tool", "$tool · $arguments") })

/** Environment for agent-level tests: no resource, token, questionnaire or provider effect is expected. */
internal fun nativeTestEnvironment(home: Path, sessionId: String) = NativeBackendEnvironment(Json, home.toString(),
    resources = NativeResources { error("No resource read") },
    processes = object : NativeProcessRecovery {
        override fun record(id: String, process: Process, attachLifetime: Boolean) { assertEquals(sessionId, id); assertEquals(42L, process.pid()) }
        override fun clear(id: String) = error("No confirmed outcome")
        override fun belongsTo(id: String, process: Process?) = false
        override fun reconcile(id: String) = error("No recovery effect")
    }, cachedAccessTokens = NativeAuthTokens { error("No token read") }, refreshedAccessTokens = NativeAuthTokens { error("No token refresh") },
    questionnaires = object : NativeQuestionnaires {
        override suspend fun ask(request: UserInteractionRequest): List<PlanningAnswer> = error("No questionnaire")
        override suspend fun beginDelivery(requestId: String): String = error("No questionnaire")
        override suspend fun finishDelivery(requestId: String, attemptId: String, outcome: QuestionnaireDeliveryOutcome): Unit = error("No questionnaire")
    }, diagnostics = NativeDiagnostics { _, _, cause, _ -> throw AssertionError(cause) },
    toolPresentation = NativeToolPresentationResolver { _, _, _ -> error("No tools") },
    providerLibrary = object : NativeProviderLibrary {
        override suspend fun shutdown() = Unit
        override suspend fun prepareForReset() = Unit
        override suspend fun resumeAfterReset() = Unit
        override fun close() = Unit
        override fun prepare() = error("No provider preparation")
        override suspend fun turn(profile: LlmProfile, messages: List<LlmMessage>, tools: List<LlmToolDefinition>,
            exchanges: List<LlmToolExchange>, accessToken: String, onUsage: (UsageCallResult) -> Unit): LlmToolTurn = error("No provider request")
        override suspend fun bridge(profile: LlmProfile, parameters: JsonObject): NativeProviderBridge = error("No provider bridge")
    }, lifecycleJournal = object : NativeLifecycleJournal {
        override suspend fun snapshot(): NativeJournalSnapshot = error("Client receives its scoped lifecycle")
        override suspend fun append(expected: NativeJournalRevision, entry: NativeJournalEntry): NativeJournalRevision? = error("Client receives its scoped lifecycle")
    })
