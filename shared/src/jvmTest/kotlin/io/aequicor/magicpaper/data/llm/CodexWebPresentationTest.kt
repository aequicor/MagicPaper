package io.aequicor.magicpaper.data.llm

import kotlinx.serialization.json.*
import kotlin.test.*

class CodexWebPresentationTest {
    private fun title(body: String) = codexWebTitle(Json.parseToJsonElement(body).jsonObject)

    @Test fun missingNullAndUnknownActionsHaveReadableFallbacks() {
        for (body in listOf("{}", """{"action":null}""", """{"action":"unexpected"}""", """{"action":{"type":"unknown"}}""")) {
            assertEquals("Веб-операция", title(body))
        }
        assertEquals("Веб-операция · kotlin", title("""{"action":null,"query":"kotlin"}"""))
    }

    @Test fun searchQueriesAndNullablePageFieldsAreReadable() {
        assertEquals("Поиск источников · a; b", title("""{"action":{"type":"search","queries":["a",null,"","b"]}}"""))
        assertEquals("Поиск источников · single", title("""{"action":{"type":"search","query":"single"}}"""))
        assertEquals("Открытие страницы", title("""{"action":{"type":"openPage","url":null}}"""))
        assertEquals("Поиск на странице · word", title("""{"action":{"type":"findInPage","pattern":"word","url":null}}"""))
    }
}
