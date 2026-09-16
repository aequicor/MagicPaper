package io.aequicor.magicpaper.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class ChatFollowUpsTest {
    @Test fun oldMessagesAndSavedSuggestionsRoundTrip() {
        val old = Json.decodeFromString<ChatMessage>("""{"id":"answer","role":"AGENT","text":"Ответ","createdAt":1}""")
        assertTrue(old.followUps.isEmpty())
        val suggested = old.copy(followUps = listOf("Разобрать пример", "Написать статью: Kotlin"))
        assertEquals(suggested, Json.decodeFromString<ChatMessage>(Json.encodeToString(suggested)))
    }
}
