package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.logging.AppLogEntry
import io.aequicor.magicpaper.logging.LogLevel
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * A tool call is an operation with a start, an outcome and a consequence, so it has to be findable in
 * diagnostics after the UI has scrolled away. Arguments, results and error sentences stay out of the
 * normal levels; only machine codes, opaque identities and durations are recorded there.
 */
class ToolCallDiagnosticsTest {
    private val context = ToolExecutionContext("p", "s", "s", "request", ToolRole.CHAT, CodingInteractionMode.CODE)
    private val four = Json.parseToJsonElement("""{"questions":[
        {"id":"a","title":"Первый?","kind":"SINGLE","options":[{"id":"x","label":"Да"},{"id":"y","label":"Нет"}]},
        {"id":"b","title":"Второй?","kind":"SINGLE","options":[{"id":"x","label":"Да"},{"id":"y","label":"Нет"}]},
        {"id":"c","title":"Третий?","kind":"SINGLE","options":[{"id":"x","label":"Да"},{"id":"y","label":"Нет"}]},
        {"id":"d","title":"Четвёртый?","kind":"SINGLE","options":[{"id":"x","label":"Да"},{"id":"y","label":"Нет"}]}]}""").jsonObject
    private val one = Json.parseToJsonElement("""{"questions":[
        {"id":"a","title":"Первый?","kind":"SINGLE","options":[{"id":"x","label":"Да"},{"id":"y","label":"Нет"}]}]}""").jsonObject

    private fun entries(event: String): List<AppLogEntry> =
        AppLog.history().filter { it.component == "coding.tool" && it.event == event }

    @Test fun applicationCallFailureIsFindableWithoutLeakingTheConversation() = runTest {
        val level = AppLog.level
        AppLog.level = LogLevel.INFO
        try {
            val host = testToolSessions(MemoryToolReceiptStore())
            val tools = host.session(context)
            assertFails { tools.call("bad-questions", "questionnaire", four) }
            val started = entries("call.started").last()
            assertEquals("questionnaire", started.fields["tool"])
            assertEquals("application", started.fields["kind"])
            assertEquals("ACTION", started.fields["category"])
            assertEquals("CODE", started.fields["mode"])
            assertEquals("STARTED", started.fields["phase"])
            assertTrue(started.fields.getValue("entityId").startsWith("id-"))
            val failed = entries("call.failed").last()
            assertEquals(LogLevel.ERROR, failed.level)
            assertEquals("validation", failed.fields["failure"])
            assertEquals("FAILED", failed.fields["phase"])
            assertTrue(failed.causeTypes.isNotEmpty(), "The rejected call must keep its cause chain")
            assertEquals(started.fields["entityId"], failed.fields["entityId"], "Both lines must describe one call")
            assertTrue((started.fields.values + failed.fields.values).none { it == "[redacted]" || it.contains("Первый") },
                "Free text belongs to the receipt and TRACE, not to the recorded fields")
            assertTrue(entries("call.payload").none { it.fields["entityId"] == started.fields["entityId"] },
                "TRACE payloads require an explicitly enabled level")
        } finally { AppLog.level = level }
    }

    @Test fun successfulCallAndNativeEngineCallReportTheirOutcome() = runTest {
        val level = AppLog.level
        AppLog.level = LogLevel.INFO
        try {
            val host = testToolSessions(MemoryToolReceiptStore())
            val tools = host.session(context)
            val call = async { tools.call("ask", "questionnaire", one) }
            runCurrent()
            val request = host.questionnaires.questions.requests.value.single()
            host.questionnaires.questions.respond(request.id, listOf(PlanningAnswer("a", selected = listOf("x"))))
            call.await()
            val completed = entries("call.completed").last()
            assertEquals("SUCCEEDED", completed.fields["phase"])
            assertNull(completed.fields["failure"])
            assertTrue(completed.fields.getValue("durationMs").all { it.isDigit() })
            val native = ToolEvent("p", "s", "request", "p/s/request/native/exec-1", "shell.exec",
                ToolCategory.EXEC, ToolPhase.STARTED, "ls")
            assertTrue(tools.recordNative(native))
            assertTrue(tools.recordNative(native.copy(phase = ToolPhase.FAILED, result = "exit 1")))
            val nativeFailure = entries("call.failed").last()
            assertEquals("native", nativeFailure.fields["kind"])
            assertEquals("shell.exec", nativeFailure.fields["tool"])
            assertEquals("engine_reported", nativeFailure.fields["failure"])
            assertTrue(nativeFailure.causeTypes.isEmpty(), "The engine reported a phase, no local exception exists")
            assertTrue(entries("call.payload").none { it.fields["entityId"] == nativeFailure.fields["entityId"] })
        } finally { AppLog.level = level }
    }

    @Test fun traceLevelAddsTheRedactedPayloadOnlyWhenEnabled() = runTest {
        val level = AppLog.level
        AppLog.level = LogLevel.TRACE
        try {
            val host = testToolSessions(MemoryToolReceiptStore())
            val tools = host.session(context)
            assertFails { tools.call("trace-questions", "questionnaire", four) }
            val failed = entries("call.failed").last()
            val payload = entries("call.payload").last { it.fields["entityId"] == failed.fields["entityId"] }
            assertContains(payload.detail.orEmpty(), "Первый")
            assertTrue(failed.fields.values.none { it.contains("Первый") })
        } finally { AppLog.level = level }
    }
}
