package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.tools.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class CodexCommandActionsTest {
    @Test fun recognizedActionsKeepTheirIdentityThroughOutputAndCompletion() {
        for ((action, tool) in listOf("read" to "read", "search" to "grep", "listFiles" to "ls")) {
            Fixture().use { f ->
                val item = command("c", """[{"type":"$action","command":"cat source.kt","path":"source.kt"}]""")
                val start = assertIs<CodingEvent.ToolStarted>(f.item("item/started", item).single())
                assertEquals(tool, start.tool)
                assertEquals("cat source.kt", start.summary)
                assertTrue(start.isExec)
                val recorder = CodingRunRecorder()
                recorder.apply(start)
                val identity = recorder.timeline().single().id
                recorder.apply(f.delta("c", "first\n").single())
                val progress = assertIs<CodingEvent.ToolProgress>(f.delta("c", "second\n").single())
                assertEquals(tool, progress.tool)
                assertEquals("first\nsecond\n", progress.resultPreview)
                recorder.apply(progress)
                // Some servers omit optional metadata from the final item.
                val finish = assertIs<CodingEvent.ToolFinished>(f.item("item/completed",
                    command("c", null, "completed", "final output")).single())
                assertEquals(tool, finish.tool)
                recorder.apply(finish)
                val step = recorder.timeline().single()
                assertEquals(identity, step.id)
                assertFalse(step.running)
                assertTrue(step.ok)
                assertEquals("final output", step.result)
                assertTrue(f.delta("c", "late output").isEmpty())
            }
        }
    }

    @Test fun missingMixedUnknownAndMalformedActionsRemainCommands() {
        val cases = listOf(null, "null", "[]", "{}", "[null]", "[{}]",
            """[{"type":"unknown"}]""", """[{"type":"futureAction"}]""",
            """[{"type":{}}]""", """[{"type":"read"},null]""",
            """[{"type":"read"},{"type":"search"}]""",
            """[{"type":"read"},{"type":"unknown"}]""")
        for (actions in cases) Fixture().use { f ->
            val item = command("c", actions)
            assertEquals("command", assertIs<CodingEvent.ToolStarted>(f.item("item/started", item).single()).tool, actions)
            assertEquals("command", assertIs<CodingEvent.ToolProgress>(f.delta("c", "output").single()).tool, actions)
            assertEquals("command", assertIs<CodingEvent.ToolFinished>(f.item("item/completed", item).single()).tool, actions)
        }
    }

    @Test fun concurrentCommandsAndThreadsDoNotShareClassificationOrOutput() {
        Fixture().use { f ->
            f.item("item/started", command("read", """[{"type":"read"},{"type":"read"}]"""))
            f.item("item/started", command("shell", null))
            assertTrue(f.delta("read", "foreign", "other-thread").isEmpty())
            f.delta("read", "one")
            assertEquals("shell output", assertIs<CodingEvent.ToolProgress>(f.delta("shell", "shell output").single()).resultPreview)
            assertEquals("onetwo", assertIs<CodingEvent.ToolProgress>(f.delta("read", "two").single()).resultPreview)
            val failed = assertIs<CodingEvent.ToolFinished>(f.item("item/completed", command("read", null, "failed", "error")).single())
            assertEquals("read", failed.tool)
            assertTrue(failed.isError)
            val declined = assertIs<CodingEvent.ToolFinished>(f.item("item/completed", command("shell", null, "declined")).single())
            assertEquals("command", declined.tool)
            assertTrue(declined.isError)
            f.item("item/started", command("read", """[{"type":"read"}]"""))
            assertEquals("new", assertIs<CodingEvent.ToolProgress>(f.delta("read", "new").single()).resultPreview)
        }
    }

    @Test fun nativeToolPipelineUsesExistingLocalizedCardsAndPreservesCommand() = runBlocking {
        for ((action, id) in listOf("read" to "file.read", "search" to "file.search", "listFiles" to "file.list", "unknown" to "shell.exec")) {
            Fixture().use { f ->
                val item = command("c", """[{"type":"$action"}]""")
                val raw = f.item("item/started", item) + f.delta("c", "output") +
                    f.item("item/completed", command("c", null, "completed", "output"))
                val tools = ToolHost(MemoryToolReceiptStore()).session(ToolExecutionContext(
                    "project", "session", "session", "request", ToolRole.WORKER, CodingInteractionMode.CODE))
                val recorder = CodingRunRecorder()
                raw.asFlow().withTools(tools).toList().forEach { recorder.apply(it) }
                val step = recorder.timeline().single()
                assertEquals(id, step.tool)
                assertEquals("⚒ ${toolDisplayName(id)} · cat source.kt", step.title)
                assertEquals(if (id == "shell.exec") CodingStepKind.EXEC else CodingStepKind.TOOL, step.kind)
                assertFalse(step.running)
                assertTrue(step.ok)
                assertEquals("output", step.result)
            }
        }
    }

    @Test fun fileChangesRemainEditsAndInterruptedReadsAreCancelled() {
        Fixture().use { f ->
            val edit = Json.parseToJsonElement("""{"id":"edit","type":"fileChange","changes":[{"path":"source.kt","kind":{"type":"update"},"diff":"+line"}]}""").jsonObject
            assertEquals("edit", assertIs<CodingEvent.ToolStarted>(f.item("item/started", edit).single()).tool)
            assertEquals("edit", assertIs<CodingEvent.ToolFinished>(f.item("item/completed", edit).single()).tool)
            val recorder = CodingRunRecorder()
            f.item("item/started", command("c", """[{"type":"read"}]""")).forEach { recorder.apply(it) }
            f.receive("turn/completed", buildJsonObject { putJsonObject("turn") { put("id", "turn"); put("status", "interrupted") } })
                .forEach { recorder.apply(it) }
            assertEquals(ToolPhase.CANCELLED, recorder.message("response", 0).steps.single().toolPhase)
            assertTrue(f.delta("c", "late").isEmpty())
        }
    }

    private fun command(id: String, actions: String?, status: String = "inProgress", output: String = "") = buildJsonObject {
        put("id", id); put("type", "commandExecution"); put("command", "cat source.kt")
        put("status", status); put("aggregatedOutput", output)
        if (actions != null) put("commandActions", Json.parseToJsonElement(actions))
    }

    @Suppress("UNCHECKED_CAST")
    private class Fixture : AutoCloseable {
        private val home = Files.createTempDirectory("codex-command-actions-")
        private val service = CodexAppServerOpenAiSubscription(Json, home)
        private val type = Class.forName("io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription\$CodingAccumulator")
        private val run = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        private val events = type.getDeclaredField("events").apply { isAccessible = true }.get(run) as Channel<CodingEvent>
        private val notify = service.javaClass.getDeclaredMethod("handleNotification", String::class.java, JsonObject::class.java).apply { isAccessible = true }
        init {
            val runs = service.javaClass.getDeclaredField("codingRuns").apply { isAccessible = true }.get(service) as MutableMap<String, Any>
            runs["thread"] = run
        }
        fun item(method: String, item: JsonObject) = receive(method, buildJsonObject { put("item", item) })
        fun delta(id: String, text: String, thread: String = "thread") = receive("item/commandExecution/outputDelta",
            buildJsonObject { put("itemId", id); put("delta", text) }, thread)
        fun receive(method: String, params: JsonObject, thread: String = "thread"): List<CodingEvent> {
            notify.invoke(service, method, JsonObject(params + ("threadId" to JsonPrimitive(thread))))
            return buildList { while (true) add(events.tryReceive().getOrNull() ?: break) }
        }
        override fun close() { service.close(); home.toFile().deleteRecursively() }
    }
}
