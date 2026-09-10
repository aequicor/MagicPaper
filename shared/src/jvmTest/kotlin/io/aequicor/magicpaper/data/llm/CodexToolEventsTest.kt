package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.tools.ToolPhase
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class CodexToolEventsTest {
    @Suppress("UNCHECKED_CAST")
    @Test fun arbitraryMcpCallsShowProgressResultsErrorsAndCancellationInTheirOwnThread() {
        val home = Files.createTempDirectory("codex-tool-events-")
        val service = CodexAppServerOpenAiSubscription(Json, home)
        try {
            val type = Class.forName("io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription\$CodingAccumulator")
            val run = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val runs = service.javaClass.getDeclaredField("codingRuns").apply { isAccessible = true }.get(service) as MutableMap<String, Any>
            runs["thread"] = run
            val events = type.getDeclaredField("events").apply { isAccessible = true }.get(run) as Channel<CodingEvent>
            val notify = service.javaClass.getDeclaredMethod("handleNotification", String::class.java, JsonObject::class.java).apply { isAccessible = true }
            val recorder = CodingRunRecorder(CodingImageInvocation("session", "run", "input", "response", "timeline"))
            fun receive(method: String, payload: String, thread: String = "thread") {
                notify.invoke(service, method, JsonObject(Json.parseToJsonElement(payload).jsonObject + ("threadId" to JsonPrimitive(thread))))
                while (true) recorder.apply(events.tryReceive().getOrNull() ?: break)
            }
            val started = """{"item":{"type":"mcpToolCall","id":"mcp-1","server":"external","tool":"lookup","arguments":{"query":"reference"}}}"""
            receive("item/started", started, "other-thread")
            assertTrue(recorder.timeline().isEmpty())
            receive("item/started", started)
            val identity = recorder.timeline().single().id
            receive("item/mcpToolCall/progress", """{"itemId":"mcp-1","message":"Reading reference"}""")
            assertEquals("Reading reference", recorder.timeline().single().result)
            receive("item/completed", """{"item":{"type":"mcpToolCall","id":"mcp-1","server":"external","tool":"lookup","status":"completed","result":{"content":[{"type":"text","text":"Reference found"},{"type":"image","id":"preview","name":"reference.png","mimeType":"image/png","data":"AQIDBA=="}]}}}""")
            assertEquals(identity, recorder.timeline().single().id)
            assertTrue(recorder.timeline().single().ok)
            assertEquals("Reference found", recorder.timeline().single().result)
            assertEquals("mcp-1:preview", recorder.timeline().single().images.single().imageId)
            assertEquals("mcp-1", recorder.timeline().single().images.single().callId)
            receive("item/started", started.replace("mcp-1", "mcp-2"))
            receive("item/completed", """{"item":{"type":"mcpToolCall","id":"mcp-2","server":"external","tool":"lookup","status":"failed","error":{"message":"Source unavailable"}}}""")
            assertFalse(recorder.timeline().last().ok)
            assertEquals("Source unavailable", recorder.timeline().last().result)
            receive("item/started", started.replace("mcp-1", "mcp-3"))
            receive("turn/completed", """{"turn":{"id":"turn","status":"interrupted"}}""")
            assertEquals(ToolPhase.CANCELLED, recorder.message("response", 0).steps.last().toolPhase)
            assertEquals(3, recorder.message("response", 0).steps.size)
        } finally { service.close(); home.toFile().deleteRecursively() }
    }
}
