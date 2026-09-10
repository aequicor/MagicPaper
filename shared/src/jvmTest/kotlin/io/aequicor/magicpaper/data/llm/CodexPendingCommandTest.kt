package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.CodingEvent
import io.aequicor.magicpaper.domain.tools.ToolPhase
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class CodexPendingCommandTest {
    private fun command(status: String = "inProgress", exit: Int? = null) = buildJsonObject {
        put("id", "exec-check"); put("type", "commandExecution"); put("command", "./gradlew :shared:check")
        put("status", status); exit?.let { put("exitCode", it) }; put("aggregatedOutput", "partial check output")
    }
    private fun response(item: JsonObject) = buildJsonObject { putJsonObject("thread") {
        put("id", "thread"); putJsonArray("turns") { add(buildJsonObject { putJsonArray("items") { add(item) } }) }
    } }

    @Test fun finalMessageDoesNotClosePendingCommandAndLateTerminalResultPrecedesFinished() {
        Stream().use { stream ->
            stream.item("item/started", command())
            assertTrue(stream.item("item/completed", command("completed")).isEmpty(), "A yielded command has no exit status yet")
            assertFalse(stream.completeTurn().any { it == CodingEvent.Finished })
            val result = stream.item("item/completed", command("completed", 0))
            assertEquals(1, result.filterIsInstance<CodingEvent.ToolFinished>().size)
            assertTrue(result.last() == CodingEvent.Finished)
            assertTrue(stream.item("item/started", command()).isEmpty())
        }
    }
    @Test fun turnPayloadRepairsLostItemNotificationWithoutDuplicateResult() {
        Stream().use { stream ->
            stream.item("item/started", command())
            val result = stream.completeTurn(command("completed", 1))
            assertTrue(result.filterIsInstance<CodingEvent.ToolFinished>().single().isError)
            assertEquals(CodingEvent.Finished, result.last())
            assertTrue(stream.item("item/completed", command("completed", 1)).isEmpty())
        }
    }
    @Test fun interruptedTurnCanCloseButDoesNotInventToolCompletion() {
        Stream().use { stream ->
            stream.item("item/started", command())
            val result = stream.completeTurn(status = "interrupted")
            assertTrue(result.none { it is CodingEvent.ToolFinished })
            assertEquals(CodingEvent.Finished, result.last())
        }
    }
    @Test fun reconciliationRequiresExactThreadCallAndExitEvidence() {
        assertTrue(CodexNativeToolResults.read(response(command()), "thread", setOf("exec-check")).isEmpty())
        assertTrue(CodexNativeToolResults.read(response(command("completed")), "thread", setOf("exec-check")).isEmpty())
        assertTrue(CodexNativeToolResults.read(response(command("completed", 0)), "other-thread", setOf("exec-check")).isEmpty())
        assertTrue(CodexNativeToolResults.read(response(command("completed", 0)), "thread", setOf("other-call")).isEmpty())
        assertEquals(ToolPhase.FAILED, CodexNativeToolResults.read(response(command("completed", 1)), "thread", setOf("exec-check")).single().phase)
        assertEquals(ToolPhase.SUCCEEDED, CodexNativeToolResults.read(response(command("completed", 0)), "thread", setOf("exec-check")).single().phase)
        val prose = buildJsonObject { put("id", "exec-check"); put("type", "agentMessage"); put("status", "completed"); put("text", "BUILD SUCCESSFUL") }
        assertTrue(CodexNativeToolResults.read(response(prose), "thread", setOf("exec-check")).isEmpty())
    }

    @Suppress("UNCHECKED_CAST")
    private class Stream : AutoCloseable {
        private val home = Files.createTempDirectory("codex-pending-command-")
        private val service = CodexAppServerOpenAiSubscription(Json, home)
        private val type = Class.forName("io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription\$CodingAccumulator")
        private val run = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        private val events = type.getDeclaredField("events").apply { isAccessible = true }.get(run) as Channel<CodingEvent>
        private val notify = service.javaClass.getDeclaredMethod("handleNotification", String::class.java, JsonObject::class.java).apply { isAccessible = true }
        init {
            (service.javaClass.getDeclaredField("codingRuns").apply { isAccessible = true }.get(service) as MutableMap<String, Any>)["thread"] = run
        }
        fun item(method: String, item: JsonObject) = receive(method, buildJsonObject { put("item", item) })
        fun completeTurn(item: JsonObject? = null, status: String = "completed") = receive("turn/completed", buildJsonObject {
            putJsonObject("turn") { put("id", "turn"); put("status", status); putJsonArray("items") { item?.let { add(it) } } }
        })
        private fun receive(method: String, params: JsonObject): List<CodingEvent> {
            notify.invoke(service, method, JsonObject(params + ("threadId" to JsonPrimitive("thread"))))
            return buildList { while (true) add(events.tryReceive().getOrNull() ?: break) }
        }
        override fun close() { service.close(); home.toFile().deleteRecursively() }
    }
}
