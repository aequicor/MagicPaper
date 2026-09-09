package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.ui.components.codingChatRows
import kotlinx.serialization.json.Json
import kotlin.test.*

class SessionContextTest {
    @Test fun reportShowsInstructionsWithoutExposingConfiguredCredential() {
        val profile = LlmProfile("p", "Profile", apiKey = "private-key", modelId = "model")
        val report = sessionContextReport(profile, "Pi", "prompt private-key", "skill@1")
        assertTrue("prompt [ключ скрыт]" in report)
        assertTrue("skill@1" in report)
        assertTrue("model" in report)
        assertFalse("private-key" in report)
    }

    @Test fun systemMessageRemainsVisibleWithSystemStepsHiddenAndRoundTrips() {
        val message = CodingMessage("context", CodingRole.AGENT, "prompt", createdAt = 1, systemContext = true)
        assertEquals(message, codingChatRows(listOf(message), hideSystemSteps = true).single().message)
        assertEquals(message, Json.decodeFromString<CodingMessage>(Json.encodeToString(CodingMessage.serializer(), message)))
        assertFalse(Json.decodeFromString<CodingMessage>("""{"id":"old","role":"AGENT","text":"answer","createdAt":1}""").systemContext)
    }
}
