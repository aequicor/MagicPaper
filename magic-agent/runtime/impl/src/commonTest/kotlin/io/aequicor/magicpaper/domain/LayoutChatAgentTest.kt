package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LayoutChatAgentTest {
    private val profile = LlmProfile("test", "Test", baseUrl = "http://localhost", modelId = "fixture")
    private val project = CodingProject("project", "Example", "/fixture", 0)
    private class Editor : LayoutEditor {
        var opened = 0
        var rendered = 0
        var published = ""
        var invalid = false
        var conflict = false
        override suspend fun open(project: CodingProject, conversationId: String): LayoutWorkspace {
            opened++; return LayoutWorkspace(project.id, "/fixture/design", "current", "PaperButton")
        }
        override suspend fun render(workspace: LayoutWorkspace, source: String): LayoutRender {
            rendered++
            return if (invalid || rendered == 1) LayoutRender(false, "Missing Frame")
            else LayoutRender(true, "", Attachment.fromBytes("preview.png", "image/png", byteArrayOf(1)))
        }
        override suspend fun publish(workspace: LayoutWorkspace, source: String) {
            if (conflict) throw LayoutEditorException("Макет изменён в редакторе. Повторите запрос.")
            published = source
        }
    }
    @Test fun requestOpensProjectRepairsValidationAndReturnsRealPreview() = runTest {
        val editor = Editor(); val prompts = mutableListOf<List<LlmMessage>>()
        val agent = LayoutChatAgent(object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
                prompts += messages.toList(); return if (prompts.size == 1) "bad" else "fixed"
            }
        }, editor)
        val result = agent.answer(project, "chat", "request", "Создай макет формы", emptyList(), profile, emptyList())
        assertEquals(1, editor.opened); assertEquals(2, editor.rendered); assertEquals("fixed\n", editor.published)
        assertTrue(prompts[1].last().content.contains("Missing Frame"))
        assertEquals(1, result.attachments.size)
    }
    @Test fun failuresNeverPublishAndMissingProjectNeverCallsModel() = runTest {
        val editor = Editor().apply { invalid = true }; var calls = 0
        val agent = LayoutChatAgent(object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String { calls++; return "bad" }
        }, editor)
        val missing = agent.answer(null, "chat", "request", "Создай макет", emptyList(), profile, emptyList())
        assertTrue(missing.text.contains("Выберите проект")); assertEquals(0, calls); assertEquals(0, editor.opened)
        val failure = agent.answer(project, "chat", "request", "Создай макет", emptyList(), profile, emptyList())
        assertEquals(3, calls); assertEquals("", editor.published); assertTrue(failure.attachments.isEmpty())
    }
    @Test fun conflictAndCancellationDoNotReportSuccess() = runTest {
        val editor = Editor().apply { rendered = 1; conflict = true }
        val agent = LayoutChatAgent(object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>) = "valid"
        }, editor)
        assertTrue(agent.answer(project, "chat", "request", "макет", emptyList(), profile, emptyList()).text.contains("изменён"))
        assertEquals("", editor.published)
        val cancelled = LayoutChatAgent(object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = throw CancellationException()
        }, editor)
        assertFailsWith<CancellationException> { cancelled.answer(project, "chat", "request", "макет", emptyList(), profile, emptyList()) }
    }
    @Test fun questionsDoNotLaunchEditorAndEditsContinueBoundConversation() {
        assertTrue(isLayoutRequest("Создай макет экрана входа"))
        assertTrue(isLayoutRequest("Please create a mockup"))
        assertTrue(isLayoutRequest("Хочу макет страницы оплаты"))
        assertFalse(isLayoutRequest("Не создавай макет, просто объясни"))
        assertFalse(isLayoutRequest("Как создать макет?"))
        assertFalse(isLayoutRequest("Расскажи о редакторе макетов"))
        assertFalse(isLayoutRequest("Добавь кнопку слева"))
        assertTrue(isLayoutRequest("Добавь кнопку слева", hasLayout = true))
    }
}
