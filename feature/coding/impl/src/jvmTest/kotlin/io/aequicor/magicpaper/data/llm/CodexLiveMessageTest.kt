package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class CodexLiveMessageTest {
    private class Stream : AutoCloseable {
        private val home = Files.createTempDirectory("codex-live-test-")
        private val service = CodexAppServerOpenAiSubscription(Json, home)
        val recorder = CodingRunRecorder()
        val planning = mutableListOf<CodingStep>()
        private val type = Class.forName("io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription\$CodingAccumulator")
        private val run = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        @Suppress("UNCHECKED_CAST")
        private val events = type.getDeclaredField("events").apply { isAccessible = true }.get(run) as Channel<CodingEvent>
        private val notify = service.javaClass.getDeclaredMethod("handleNotification", String::class.java, JsonObject::class.java)
            .apply { isAccessible = true }
        init {
            @Suppress("UNCHECKED_CAST")
            val runs = service.javaClass.getDeclaredField("codingRuns").apply { isAccessible = true }.get(service) as MutableMap<String, Any>
            runs["thread"] = run
            @Suppress("UNCHECKED_CAST")
            val turns = service.javaClass.getDeclaredField("turns").apply { isAccessible = true }.get(service) as MutableMap<String, Any>
            turns["thread"] = CodexAppServerOpenAiSubscription.TurnAccumulator(planning::add)
        }
        fun receive(method: String, payload: String) {
            notify.invoke(service, method, JsonObject(Json.parseToJsonElement(payload).jsonObject + ("threadId" to JsonPrimitive("thread"))))
            while (true) recorder.apply(events.tryReceive().getOrNull() ?: break)
        }
        fun start(id: String, kind: String = "agentMessage") = receive("item/started", """{"item":{"type":"$kind","id":"$id"}}""")
        fun text(id: String, text: String) = receive("item/agentMessage/delta", """{"itemId":"$id","delta":${JsonPrimitive(text)}}""")
        fun thought(id: String, text: String, index: Int = 0, full: Boolean = false) = receive(
            if (full) "item/reasoning/textDelta" else "item/reasoning/summaryTextDelta",
            """{"itemId":"$id","summaryIndex":$index,"contentIndex":$index,"delta":${JsonPrimitive(text)}}""")
        fun finish(id: String, text: String) = receive("item/completed", """{"item":{"type":"agentMessage","id":"$id","text":${JsonPrimitive(text)}}}""")
        fun answers() = recorder.timeline().filter { it.kind == CodingStepKind.ANSWER }
        override fun close() { service.close(); home.toFile().deleteRecursively() }
    }

    @Test fun interleavedReasoningDoesNotSplitOrDuplicateAnAnswer() = Stream().use { stream ->
        stream.start("a")
        stream.text("a", "Первая часть. ")
        val id = stream.answers().single().id
        stream.start("r", "reasoning")
        stream.thought("r", "Проверяю")
        stream.text("a", "Вторая часть.")
        assertEquals(listOf("Первая часть. Вторая часть."), stream.answers().map { it.title })
        assertEquals(id, stream.answers().single().id)
        stream.finish("a", "Первая часть. Вторая часть.")
        assertEquals(listOf("Первая часть. Вторая часть."), stream.answers().map { it.title })
        assertEquals(id, stream.answers().single().id)
    }

    @Test fun lateFinalUpdatesItsOwnItemEvenAfterAnotherAnswerStarted() = Stream().use { stream ->
        stream.start("a"); stream.text("a", "Первый")
        stream.start("b"); stream.text("b", "Второй")
        val ids = stream.answers().map { it.id }
        stream.finish("a", "Первый ответ")
        stream.finish("b", "Второй ответ")
        stream.finish("a", "Первый ответ")
        assertEquals(listOf("Первый ответ", "Второй ответ"), stream.answers().map { it.title })
        assertEquals(ids, stream.answers().map { it.id })
    }

    @Test fun summaryKeepsItsItemAcrossInterleavedStartsAndSeparatesParagraphs() = Stream().use { stream ->
        stream.start("r", "reasoning"); stream.thought("r", "Первая мысль")
        stream.start("a"); stream.text("a", "Ответ")
        stream.thought("r", "Вторая мысль", index = 1)
        stream.thought("r", ".", index = 0)
        val summaries = stream.planning.filter { it.kind == CodingStepKind.SUMMARY }
        assertEquals(setOf("r"), summaries.map { it.callId }.toSet())
        assertEquals("Первая мысль.\n\nВторая мысль", summaries.last().title)
        assertTrue(summaries.first().id.isNotBlank())
        assertEquals(1, summaries.map { it.id }.toSet().size)
    }

    @Test fun fullReasoningAndSummaryRemainIndependentStreams() = Stream().use { stream ->
        stream.start("r", "reasoning")
        stream.thought("r", "Подробная мысль", full = true)
        stream.thought("r", "Краткая мысль")
        stream.thought("r", " продолжение", full = true)
        assertEquals("Подробная мысль продолжение", stream.recorder.timeline().single { it.kind == CodingStepKind.THINKING }.title)
        assertEquals("Краткая мысль", stream.recorder.draft(true).reasoningSummary)
    }
}
