package io.aequicor.magicpaper.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class PlanningReplyPreviewTest {
    @Test fun everyPrefixShowsOnlyReadableReplyWithoutBrokenEscapes() {
        val reply = "Проверяю \"формат\"\nПуть C:\\work — готово 🔀"
        val raw = buildJsonObject {
            put("actions", buildJsonArray { add(buildJsonObject { put("reply", "Не показывать вложенное поле") }) })
            put("reply", reply)
            put("askUser", false)
        }.toString()
        for (end in 0..raw.length) {
            val preview = planningReplyPreview(raw.take(end))
            assertTrue(reply.startsWith(preview), "Unexpected preview at $end: $preview")
            assertFalse(preview.lastOrNull() in '\uD800'..'\uDBFF')
        }
        assertEquals(reply, planningReplyPreview(raw))
        assertEquals(reply, planningReplyPreview("```json\n$raw\n```"))
    }

    @Test fun unicodeEscapesCanBeSplitBetweenAnyTwoCharacters() {
        val raw = """{"reply":"\u0410\n\uD83D\uDE00","actions":[]}"""
        for (end in 0..raw.length) assertTrue("А\n😀".startsWith(planningReplyPreview(raw.take(end))))
        assertEquals("А\n😀", planningReplyPreview(raw))
        assertEquals("", planningReplyPreview("```j"))
        assertEquals("", planningReplyPreview("""{"actions":[{"reply":"Hidden"}]}"""))
        assertEquals("Проверяю файлы", planningReplyPreview("Проверяю файлы"))
    }
}
