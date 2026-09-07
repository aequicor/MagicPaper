package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class CodexReasoningEventsTest {
    @Suppress("UNCHECKED_CAST")
    @Test fun streamedSummaryAndFinalSummaryStaySeparateFromCommand() {
        val home = Files.createTempDirectory("codex-reasoning-test-")
        val service = CodexAppServerOpenAiSubscription(Json, home)
        try {
            val type = Class.forName("io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription\$CodingAccumulator")
            val run = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val runs = service.javaClass.getDeclaredField("codingRuns").apply { isAccessible = true }
                .get(service) as MutableMap<String, Any>
            runs["thread"] = run
            val events = type.getDeclaredField("events").apply { isAccessible = true }.get(run) as Channel<CodingEvent>
            val notify = service.javaClass.getDeclaredMethod("handleNotification", String::class.java, JsonObject::class.java)
                .apply { isAccessible = true }
            val recorder = CodingRunRecorder()
            fun receive(method: String, payload: String) {
                val params = Json.parseToJsonElement(payload).jsonObject + ("threadId" to JsonPrimitive("thread"))
                notify.invoke(service, method, JsonObject(params))
                while (true) recorder.apply(events.tryReceive().getOrNull() ?: break)
            }
            receive("item/started", """{"item":{"type":"reasoning","id":"r"}}""")
            receive("item/reasoning/summaryTextDelta", """{"itemId":"r","summaryIndex":0,"delta":"Проверю "}""")
            receive("item/reasoning/summaryTextDelta", """{"itemId":"r","summaryIndex":0,"delta":"сборку"}""")
            assertEquals("Проверю сборку", recorder.draft(true).thinking)
            receive("item/completed", """{"item":{"type":"reasoning","id":"r","summary":["Проверю сборку проекта"],"content":[]}}""")
            receive("item/started", """{"item":{"type":"commandExecution","id":"c","command":"bash -lc ./gradlew"}}""")
            val draft = recorder.draft(true)
            assertEquals("", draft.thinking)
            assertEquals("Проверю сборку проекта", draft.steps.single { it.kind == CodingStepKind.THINKING }.title)
            assertTrue(draft.steps.last().running)
            assertEquals(CodingStepKind.EXEC, draft.steps.last().kind)
            receive("guardianWarning", """{"message":"Запрос доступа отклонён"}""")
            assertTrue(recorder.draft(true).steps.any { it.title.contains("Проверка разрешений: Запрос доступа отклонён") })
        } finally {
            service.close()
            home.toFile().deleteRecursively()
        }
    }
}
