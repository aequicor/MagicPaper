package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class CodexUsageEventsTest {
    @Suppress("UNCHECKED_CAST")
    @Test fun routesNativeUsageCompactionAndSearchWithoutMixingThreads() {
        val home = Files.createTempDirectory("codex-usage-events")
        val service = CodexAppServerOpenAiSubscription(Json, home)
        try {
            val type = Class.forName("io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription\$CodingAccumulator")
            val run = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val runs = service.javaClass.getDeclaredField("codingRuns").apply { isAccessible = true }.get(service) as MutableMap<String, Any>
            runs["thread"] = run
            val events = type.getDeclaredField("events").apply { isAccessible = true }.get(run) as Channel<CodingEvent>
            val notify = service.javaClass.getDeclaredMethod("handleNotification", String::class.java, JsonObject::class.java).apply { isAccessible = true }
            fun receive(method: String, body: String, thread: String = "thread"): List<CodingEvent> {
                notify.invoke(service, method, JsonObject(Json.parseToJsonElement(body).jsonObject + ("threadId" to JsonPrimitive(thread))))
                return buildList { while (true) add(events.tryReceive().getOrNull() ?: break) }
            }
            receive("turn/started", """{"turn":{"id":"turn"}}""")
            val payload = """{"turnId":"turn","tokenUsage":{"total":{"inputTokens":120,"cachedInputTokens":100,"outputTokens":30,"reasoningOutputTokens":20,"totalTokens":150},"last":{"inputTokens":120,"cachedInputTokens":100,"outputTokens":30,"reasoningOutputTokens":20,"totalTokens":150},"modelContextWindow":1000}}"""
            assertTrue(receive("thread/tokenUsage/updated", payload, "other").isEmpty())
            val usage = receive("thread/tokenUsage/updated", payload)
            assertEquals(20L, usage.filterIsInstance<CodingEvent.UsageObserved>().single().tokens.input)
            assertEquals(150L, usage.filterIsInstance<CodingEvent.ContextUpdated>().single().used)
            assertEquals(1000L, usage.filterIsInstance<CodingEvent.ContextUpdated>().single().limit)
            val recorder = CodingRunRecorder()
            receive("item/started", """{"item":{"type":"contextCompaction","id":"c"}}""").forEach(recorder::apply)
            receive("item/completed", """{"item":{"type":"contextCompaction","id":"c"}}""").forEach(recorder::apply)
            receive("thread/compacted", "{}").forEach(recorder::apply)
            assertEquals(1, recorder.timeline().count { it.kind == CodingStepKind.SYSTEM })
            assertFalse(recorder.timeline().single().running)
            val search = receive("item/completed", """{"item":{"type":"webSearch","id":"w","action":{"type":"search","queries":["a","b"]}}}""")
            assertEquals(2L, search.filterIsInstance<CodingEvent.SearchObserved>().single().requests)
            val page = receive("item/completed", """{"item":{"type":"webSearch","id":"page","action":{"type":"openPage","url":"https://example.com"}}}""")
            assertTrue(page.filterIsInstance<CodingEvent.SearchObserved>().single().content)
            receive("item/started", """{"item":{"type":"contextCompaction","id":"failed"}}""")
            val failed = receive("turn/completed", """{"turn":{"id":"turn","error":{"message":"failed"}}}""")
            assertEquals(CompactionPhase.FAILED, failed.filterIsInstance<CodingEvent.Compaction>().single().status.phase)
        } finally { service.close(); home.toFile().deleteRecursively() }
    }
}
