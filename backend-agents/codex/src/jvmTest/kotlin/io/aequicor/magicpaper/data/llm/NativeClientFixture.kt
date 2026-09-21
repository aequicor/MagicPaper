package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.*
import java.nio.file.Path
import kotlinx.serialization.json.Json

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
