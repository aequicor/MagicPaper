package io.aequicor.magicpaper.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodingImageLinkTest {
    private val invocation = CodingImageInvocation(
        sessionId = "session-a",
        invocationId = "run-a",
        inputMessageId = "user-a",
        responseMessageId = "agent-a",
        responseTimelineId = "timeline-a",
    )

    @Test fun inputImagesKeepTheExactInvocationAndNeverMatchAnotherMessage() {
        val attachment = Attachment("input", "design.png", "image/png", 4, "AQIDBA==", AttachmentKind.IMAGE)
        val image = checkNotNull(attachment.asCodingInputImage(invocation))
        val owner = CodingMessage("user-a", CodingRole.USER, "inspect", createdAt = 0, images = listOf(image))
        assertTrue(image.isInputFor(owner))
        assertFalse(image.isInputFor(owner.copy(id = "another-user")))
        assertFalse(image.isInputFor(owner.copy(role = CodingRole.AGENT)))
        assertEquals("session-a", image.sessionId)
        assertEquals("run-a", image.invocationId)
        assertEquals("timeline-a", image.timelineId)
        assertEquals(null, image.callId)
    }

    @Test fun terminalArtifactStaysWithItsCallAcrossStreamingFailureAndRepeatedCompletion() {
        val recorder = CodingRunRecorder(invocation)
        recorder.apply(CodingEvent.ToolStarted("render", "", callId = "call-a"))
        recorder.apply(CodingEvent.ToolProgress("render", "call-a", "working"))
        assertTrue(recorder.timeline().single().images.isEmpty(), "progress text must not be parsed as an image")
        val terminal = CodingEvent.ToolFinished("render", false, callId = "call-a", images = listOf(
            CodingImageArtifact("result", "render.png", "image/png", 4, "AQIDBA=="),
        ))
        recorder.apply(terminal)
        recorder.apply(terminal) // native reconnect may replay a terminal item
        recorder.apply(CodingEvent.Failed("next operation failed"))
        val response = recorder.message("agent-a", 1)
        val step = response.steps.first { it.callId == "call-a" }
        assertEquals(1, step.images.size)
        assertTrue(step.images.single().isResultFor(response, step))
        assertFalse(step.images.single().isResultFor(response, step.copy(callId = "call-b")))
        assertEquals("session-a", step.images.single().sessionId)
        assertEquals("run-a", step.images.single().invocationId)
        assertEquals("timeline-a", step.images.single().timelineId)
        assertEquals("agent-a", step.images.single().ownerMessageId)
    }

    @Test fun artifactsWithoutExactTerminalCallOrTypedImageAreIgnored() {
        val recorder = CodingRunRecorder(invocation)
        recorder.apply(CodingEvent.ToolStarted("render", "", callId = "call-a"))
        recorder.apply(CodingEvent.ToolFinished("render", false, callId = "", resultPreview = "data:image/png;base64,AQIDBA==",
            images = listOf(CodingImageArtifact(mimeType = "image/png", sizeBytes = 4, dataBase64 = "AQIDBA=="))))
        recorder.apply(CodingEvent.ToolFinished("render", false, callId = "call-a", resultPreview = "image.png",
            images = listOf(CodingImageArtifact(mimeType = "text/plain", sizeBytes = 4, dataBase64 = "AQIDBA=="))))
        assertTrue(recorder.timeline().single().images.isEmpty())
    }

    @Test fun recoveryReusesThePersistedInvocationAndLegacyHistoryDefaultsToNoImages() {
        val checkpoint = CodingRunCheckpoint("user-a", "inspect", responseId = "agent-a", runId = "run-a",
            responseTimelineId = "timeline-a")
        assertEquals(checkpoint.responseTimelineId, checkpoint.copy(prompt = "continue").responseTimelineId)
        val recovered = CodingRunRecorder(invocation)
        recovered.apply(CodingEvent.ToolStarted("render", "", callId = "call-a"))
        recovered.apply(CodingEvent.ToolFinished("render", false, callId = "call-a", images = listOf(
            CodingImageArtifact(mimeType = "image/png", sizeBytes = 4, dataBase64 = "AQIDBA=="),
        )))
        val persisted = recovered.message("agent-a", 1)
        assertEquals("timeline-a", persisted.timelineId)
        val restored = Json.decodeFromString<CodingMessage>(Json.encodeToString(persisted))
        assertEquals(persisted.steps.single().images, restored.steps.single().images)
        val legacy = Json.decodeFromString<CodingMessage>("""{"id":"old","role":"USER","text":"old","createdAt":1,"attachments":[]}""")
        assertTrue(legacy.images.isEmpty())
        assertTrue(legacy.steps.isEmpty())
    }
}
