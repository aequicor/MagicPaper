package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.CodingEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatResearchModeTest {
    @Test fun desktopChatAlwaysUsesRestrictedResearchMode() {
        val chat = ChatSession("chat", "Исследование", 1, 2, nativeSessionId = "native",
            engine = CodingEngine.CODEX, acquireComputerAccess = true)

        val coding = chat.asResearchCodingSession("chat-chat")

        assertEquals("native", coding.piSessionId)
        assertEquals(CodingEngine.CODEX, coding.engine)
        assertTrue(coding.researchMode)
        assertFalse(coding.acquireComputerAccess)
    }
}
