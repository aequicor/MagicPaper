package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import java.lang.reflect.Proxy
import kotlin.test.*

class LoggingCodingServiceTest {
    private val calls = mutableListOf<String>()
    private val service = Proxy.newProxyInstance(CodingService::class.java.classLoader, arrayOf(CodingService::class.java)) { _, method, _ ->
        calls += method.name; null
    } as CodingService

    @Test fun aClickIsLoggedByItsActionAndIdentityWithoutTheUsersWords() {
        val logged = LoggingCodingService(service)
        val file = Attachment("file", "private-notes.txt", "text/plain", 3, "YWJj", AttachmentKind.TEXT)
        logged.sendCodingPromptTo("session-1", "secret prompt words", listOf(file))
        assertEquals(listOf("sendCodingPromptTo"), calls, "the action reaches the service unchanged")
        val entry = AppLog.history().last { it.component == "coding" && it.event == "action" }
        assertEquals("sendCodingPromptTo", entry.fields["action"])
        assertEquals("1", entry.fields["attachmentsCount"])
        assertNotNull(entry.fields["sessionId"])
        assertFalse("secret" in entry.line() || "private-notes" in entry.line(), entry.line())
    }

    @Test fun lifecycleAndKeystrokesPassThroughUnlogged() {
        val logged = LoggingCodingService(service)
        val before = AppLog.history().count { it.component == "coding" && it.event == "action" }
        logged.setVisible(true)
        logged.updateQuestionnaireDraft("question", QuestionnaireDraft())
        assertEquals(listOf("setVisible", "updateQuestionnaireDraft"), calls)
        assertEquals(before, AppLog.history().count { it.component == "coding" && it.event == "action" })
    }
}
