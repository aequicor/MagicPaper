package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.*
import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.Json
import kotlin.test.*

class ConversationHistoryTextTest {
    @Test fun forkPreservesImageBytesWithIndependentMessageOwnership() {
        val image = CodingImageReference("image", CodingImageSource.TOOL_RESULT, "source", "invocation", "timeline", "answer",
            callId = "call", name = "result.png", mimeType = "image/png", sizeBytes = 3, locator = CodingImageLocator.InlineBase64("AQID"))
        val step = CodingStep(CodingStepKind.TOOL, "Image", callId = "call", images = listOf(image))
        val message = CodingMessage("answer", CodingRole.AGENT, "Image result", steps = listOf(step), createdAt = 1,
            images = listOf(image), timelineId = "timeline")
        val copy = message.forFork("fork")
        assertNotEquals(message.id, copy.id)
        assertEquals("fork", copy.images.single().sessionId)
        assertTrue(copy.images.single().isResultFor(copy, copy.steps.single()))
        assertTrue(copy.steps.single().images.single().isResultFor(copy, copy.steps.single()))
        assertEquals(image.locator, copy.images.single().locator)
    }

    @Test fun copyIncludesUntruncatedTextToolResultsSourcesAndTextAttachments() {
        val text = "Long message\n".repeat(4000)
        val output = "Tool output\n".repeat(4000)
        val file = Attachment.fromBytes("notes.txt", "text/plain", "complete file".encodeToByteArray())
        val coding = CodingMessage("a", CodingRole.AGENT, text,
            steps = listOf(CodingStep(CodingStepKind.TOOL, "Read file", result = output), CodingStep(CodingStepKind.ANSWER, text)), createdAt = 1)
        assertContains(coding.fullCopyText(), text)
        assertContains(coding.fullCopyText(), output)
        val chat = ChatMessage("u", ChatRole.USER, text, 1, attachments = listOf(file),
            sources = listOf(SearchHit("Reference", "https://example.invalid/source", "source detail")))
        assertContains(chat.fullCopyText(), "complete file")
        assertContains(chat.fullCopyText(), "source detail")
        assertContains(chat.fullCopyText(), text)
    }

    @Test fun removedIdsSurviveReopenAndCachedProjectionWrites() = runTest {
        val store = InMemoryKeyValueStore(); val json = Json { encodeDefaults = true }
        val events = InMemoryEventJournal()
        val repo = journalCodingProjects(store, json, events)
        repo.createTestProject(CodingProject("p", "Project", "/fixture", 1))
        repo.createTestSession(CodingSession("s", "p", "Task", 1, engine = CodingEngine.PI))
        val a = CodingMessage("a", CodingRole.USER, "delete me", createdAt = 1)
        val b = CodingMessage("b", CodingRole.AGENT, "keep me", createdAt = 2)
        repo.publishTestHistory("p", "s", listOf(a, b))
        repo.dispatch("p", CodingMachine.Intent.ReplaceHistory(CodingMachine.SessionRef("s", 0), listOf(a, b), listOf(b)))
        repo.publishTestHistory("p", "s", listOf(a, b))
        assertEquals(listOf(b), repo.messages("p", "s"))
        val reopened = journalCodingProjects(store, json, events)
        assertEquals(listOf(b), reopened.messages("p", "s"))
        val edited = CodingMessage("edited", CodingRole.USER, "new question", createdAt = 3)
        reopened.dispatch("p", CodingMachine.Intent.ReplaceHistory(CodingMachine.SessionRef("s", 0), listOf(b), listOf(b, edited)))
        reopened.publishTestHistory("p", "s", listOf(a, b))
        assertEquals(listOf(b, edited), reopened.messages("p", "s"), "Late projections cannot drop the edited input")
        assertFails { reopened.dispatch("p", CodingMachine.Intent.ReplaceHistory(CodingMachine.SessionRef("s", 0), listOf(a, b), emptyList())) }
        reopened.publishTestHistory("p", "s", listOf(a, b))
        assertEquals(listOf(b, edited), reopened.messages("p", "s"))
    }
}
