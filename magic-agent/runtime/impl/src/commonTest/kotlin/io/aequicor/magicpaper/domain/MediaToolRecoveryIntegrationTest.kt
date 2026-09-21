package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.MediaStore
import io.aequicor.magicpaper.domain.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

import io.aequicor.magicpaper.domain.MediaServiceFixtures.Fixture

@OptIn(ExperimentalCoroutinesApi::class)
class MediaToolRecoveryIntegrationTest {
    @Test fun confirmedPromptRejectionKeepsCapabilityWithoutUnknownToolOutcome() = runTest {
        val f = Fixture(backgroundScope)
        f.verify()
        val receipts = MemoryToolReceiptStore()
        val host = testToolSessions(receipts, mediaGeneration = f.service)
        val context = host.media.prepareContext(ToolExecutionContext("project", "session", "session", "request",
            ToolRole.CHAT, CodingInteractionMode.CODE), SessionMediaTools())
        f.gateway.submitAction = { throw MediaGatewayException(MediaFailureKind.REJECTED, "Rejected") }
        assertFailsWith<IllegalStateException> {
            host.session(context).call("generation", "image.generate", buildJsonObject { put("prompt", "Image") })
        }
        assertEquals(ToolPhase.FAILED, receipts.get("project/session/request/generation")!!.phase)
        assertTrue(f.service.available(MediaKind.IMAGE))
    }

    @Test fun temporarySubmissionRefusalKeepsVerifiedCapabilityAndDoesNotQuarantine() = runTest {
        for (status in listOf(429, null)) {
            val f = Fixture(backgroundScope)
            f.verify()
            val receipts = MemoryToolReceiptStore()
            var quarantines = 0
            val host = testToolSessions(receipts, mediaGeneration = f.service,
                unknownOutcome = { _, _ -> quarantines++ })
            val context = host.media.prepareContext(ToolExecutionContext("project", "session", "session", "request",
                ToolRole.CHAT, CodingInteractionMode.CODE), SessionMediaTools())
            f.gateway.submitAction = { throw MediaGatewayException(MediaFailureKind.TRANSIENT, "Throttled", statusCode = status) }
            assertFailsWith<IllegalStateException> {
                host.session(context).call("limited", "image.generate", buildJsonObject { put("prompt", "Image") })
            }
            assertEquals(ToolPhase.FAILED, receipts.get("project/session/request/limited")?.phase)
            assertTrue(f.service.available(MediaKind.IMAGE))
            assertEquals(0, quarantines)
            assertEquals(2, f.gateway.submissions)
        }
    }

    @Test fun unavailableAndDisabledToolsAreAbsentAndMidRunDisableRejectsSubmission() = runTest {
        val f = Fixture(backgroundScope)
        f.verify()
        var enabled = true
        val host = testToolSessions(MemoryToolReceiptStore(), mediaGeneration = f.service,
                mediaAllowed = { _, _ -> enabled })
        val context = ToolExecutionContext("project", "session", "session", "request", ToolRole.CHAT, CodingInteractionMode.RESEARCH)
        val allowed = host.media.prepareContext(context, SessionMediaTools())
        assertEquals(setOf(MediaKind.IMAGE), allowed.mediaCapabilities)
        val tools = host.session(allowed)
        assertTrue(tools.definitions.any { it.id == "image.generate" })
        assertFalse(tools.definitions.any { it.id == "video.generate" })
        enabled = false
        assertFailsWith<IllegalStateException> { tools.call("blocked", "image.generate", buildJsonObject { put("prompt", "Image") }) }
        assertEquals(1, f.gateway.submissions)
        assertTrue(host.media.prepareContext(context, SessionMediaTools(images = false)).mediaCapabilities.isEmpty())
    }

    @Test fun successfulRecoverySettlesOnlyItsExactUnknownToolReceipt() = runTest {
        val receipts = MemoryToolReceiptStore()
        val receiptOwner = DefaultMediaToolReceiptOwner(receipts) { asset -> "/media/${asset.id}" }
        val f = Fixture(backgroundScope, onTerminal = receiptOwner::reconcileCompletion)
        f.verify()
        val host = testToolSessions(receipts, mediaGeneration = f.service, mediaToolReceipts = receiptOwner)
        val context = host.media.prepareContext(ToolExecutionContext("project", "session", "session", "request",
            ToolRole.CHAT, CodingInteractionMode.CODE), SessionMediaTools())
        f.gateway.submitAction = { MediaSubmission.Accepted("provider-job") }
        val completed = CompletableDeferred<MediaPollResult>()
        f.gateway.pollAction = { completed.await() }
        val attempt = launch { host.session(context).call("media", "image.generate", buildJsonObject { put("prompt", "Image") }) }
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        attempt.cancelAndJoin()
        val receipt = assertNotNull(receipts.get("project/session/request/media"))
        assertEquals(ToolPhase.UNKNOWN, receipt.phase)
        completed.complete(MediaPollResult.Completed(MediaRemoteOutput(MediaKind.IMAGE, url = "https://media.example/ready")))
        val result = assertNotNull(f.service.recoverMedia(receipt.id))
        assertEquals(ToolPhase.SUCCEEDED, receipts.get(receipt.id)?.phase)
        assertFailsWith<IllegalArgumentException> {
            host.media.reconcileCompletion(f.owner.copy(callId = receipt.id, runtimeGeneration = 99), receipt.operationId, result)
        }
        assertEquals(2, f.gateway.submissions)
    }

    @Test fun startupSettlesLostReceiptAcknowledgementFromAlreadySavedAsset() = runTest {
        val saved = MemoryToolReceiptStore()
        val receipts = object : ToolReceiptStore by saved {
            var failCompletion = true
            override suspend fun save(receipt: ToolReceipt) {
                if (failCompletion && receipt.phase == ToolPhase.SUCCEEDED) {
                    failCompletion = false
                    error("Lost receipt write")
                }
                saved.save(receipt)
            }
        }
        val receiptOwner = DefaultMediaToolReceiptOwner(receipts) { asset -> "/media/${asset.id}" }
        val f = Fixture(backgroundScope, onTerminal = receiptOwner::reconcileCompletion)
        f.verify()
        val host = testToolSessions(receipts, mediaGeneration = f.service, mediaToolReceipts = receiptOwner)
        val context = host.media.prepareContext(ToolExecutionContext("project", "session", "session", "request",
            ToolRole.CHAT, CodingInteractionMode.CODE), SessionMediaTools())
        assertFailsWith<IllegalStateException> {
            host.session(context).call("media", "image.generate", buildJsonObject { put("prompt", "Image") })
        }
        assertEquals(ToolPhase.UNKNOWN, saved.get("project/session/request/media")?.phase)
        f.service.recoverPending()
        assertEquals(ToolPhase.SUCCEEDED, saved.get("project/session/request/media")?.phase)
        assertEquals(2, f.gateway.submissions)
    }

}
