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
class MediaGenerationServiceTest {
    @Test fun verifiedCapabilityGeneratesOnceAndKeepsBinaryOutsideMetadata() = runTest {
        val f = Fixture(backgroundScope)
        assertFalse(f.service.available(MediaKind.IMAGE))
        assertEquals(MediaAvailability.AVAILABLE, f.verify().availability)
        val updates = mutableListOf<GeneratedMedia>()
        val first = f.service.generate(f.owner, "operation", f.request) { updates += it }
        val repeated = f.service.generate(f.owner, "operation", f.request)
        assertEquals(first, repeated)
        assertEquals(2, f.gateway.submissions, "One explicit probe and one generation")
        assertEquals(MediaPhase.READY, updates.last().phase)
        assertEquals("tool-call", first.id)
        assertContentEquals(byteArrayOf(1, 2, 3), f.service.read(assertNotNull(first.asset)))
        assertTrue(f.store.metadata.values.none { "private-key" in it })
        assertEquals(2, f.usage.state.value.records.size)
    }

    @Test fun changedCredentialCannotBeActivatedByAnOlderProbe() = runTest {
        val f = Fixture(backgroundScope)
        val result = CompletableDeferred<MediaSubmission>()
        f.gateway.submitAction = { result.await() }
        val probe = async { f.verify() }
        runCurrent()
        f.profiles.value = listOf(f.profile.copy(apiKey = "replacement-key"))
        result.complete(MediaSubmission.Completed(MediaRemoteOutput(MediaKind.IMAGE, url = "https://media.example/image")))
        assertEquals(MediaAvailability.AVAILABLE, probe.await().availability)
        assertFalse(f.service.available(MediaKind.IMAGE))
        assertTrue(f.store.metadata.values.none { "private-key" in it || "replacement-key" in it })
    }

    @Test fun noAuthenticationLocalConnectionCanBeVerified() = runTest {
        val f = Fixture(backgroundScope)
        f.profiles.value = listOf(f.profile.copy(apiKey = "", authType = null))
        f.verify()
        assertTrue(f.service.available(MediaKind.IMAGE))
    }

    @Test fun paidProbeShowsLivePlaceholderAndRecoveryReplacesUnknownPreview() = runTest {
        val f = Fixture(backgroundScope, timeoutMillis = 5)
        f.gateway.submitAction = { MediaSubmission.Accepted("probe-job") }
        f.gateway.pollAction = { MediaPollResult.Pending }
        val probe = async { f.verify() }
        runCurrent()
        val checking = assertNotNull(f.service.state.value[MediaKind.IMAGE])
        assertEquals(MediaAvailability.CHECKING, checking.availability)
        assertEquals(MediaPhase.GENERATING, checking.preview?.phase)
        assertEquals(1024, checking.preview?.width)
        val unknown = probe.await()
        assertEquals(MediaPhase.UNKNOWN, unknown.preview?.phase)
        f.gateway.pollAction = { MediaPollResult.Completed(MediaRemoteOutput(MediaKind.IMAGE, url = "https://media.example/ready")) }
        f.service.recoverMedia(assertNotNull(unknown.preview).id)
        assertEquals(MediaAvailability.AVAILABLE, f.service.state.value[MediaKind.IMAGE]?.availability)
        assertEquals(MediaPhase.READY, f.service.state.value[MediaKind.IMAGE]?.preview?.phase)
        assertEquals(1, f.gateway.submissions)
    }

    @Test fun olderProbeFailureCannotReplaceANewerProofForTheSameConfiguration() = runTest {
        val f = Fixture(backgroundScope)
        val first = CompletableDeferred<MediaSubmission>()
        f.gateway.submitAction = { first.await() }
        val oldCheck = async { f.verify() }
        runCurrent()
        f.gateway.submitAction = { MediaSubmission.Completed(MediaRemoteOutput(MediaKind.IMAGE, url = "https://media.example/new")) }
        f.verify()
        val latest = f.service.state.value[MediaKind.IMAGE]
        first.completeExceptionally(MediaGatewayException(MediaFailureKind.AUTHENTICATION, "Old rejection"))
        assertEquals(MediaAvailability.UNAVAILABLE, oldCheck.await().availability)
        assertEquals(latest, f.service.state.value[MediaKind.IMAGE])
        assertTrue(f.service.available(MediaKind.IMAGE))
    }

    @Test fun deletingOneOwnerKeepsSharedImmutableMediaReadable() = runTest {
        val f = Fixture(backgroundScope)
        f.verify()
        val generated = f.service.generate(f.owner, "original", f.request)
        val asset = assertNotNull(generated.asset)
        f.service.deleteSession(f.owner.sessionId)
        assertContentEquals(byteArrayOf(1, 2, 3), f.service.read(asset))
        assertFalse(f.service.operations.value.containsKey(f.owner.callId))
        assertFailsWith<IllegalStateException> { f.service.recover("original") }
    }

    @Test fun lostSubmissionAcknowledgementNeverCreatesAnotherGeneration() = runTest {
        val f = Fixture(backgroundScope)
        f.verify()
        f.gateway.submitAction = { throw MediaGatewayException(MediaFailureKind.UNKNOWN_OUTCOME, "Lost acknowledgement") }
        assertFailsWith<MediaGatewayException> { f.service.generate(f.owner, "uncertain", f.request) }
        assertEquals(MediaPhase.UNKNOWN, f.service.operations.value.getValue("tool-call").phase)
        assertFailsWith<IllegalStateException> { f.service.generate(f.owner, "uncertain", f.request) }
        assertFailsWith<IllegalStateException> { f.service.recover("uncertain") }
        assertEquals(2, f.gateway.submissions)
    }

    @Test fun cancelledAwaiterLeavesAcceptedJobRunningWithoutSubmittingAgain() = runTest {
        val f = Fixture(backgroundScope)
        f.verify()
        f.gateway.submitAction = { MediaSubmission.Accepted("provider-job") }
        val completed = CompletableDeferred<MediaPollResult>()
        f.gateway.pollAction = { completed.await() }
        val attempt = launch { f.service.generate(f.owner, "accepted", f.request) }
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        attempt.cancelAndJoin()
        assertEquals(MediaPhase.GENERATING, f.service.operations.value.getValue("tool-call").phase)
        completed.complete(MediaPollResult.Completed(MediaRemoteOutput(MediaKind.IMAGE, url = "https://media.example/result")))
        val restored = assertNotNull(f.service.recoverMedia("tool-call"))
        assertEquals(MediaPhase.READY, restored.phase)
        assertEquals(2, f.gateway.submissions)
        assertEquals(MediaPhase.READY, f.service.operations.value.getValue("tool-call").phase)
    }

    @Test fun modelDefaultsChooseSupportedProbeAndToolSizes() {
        val selection = MediaModelSelection("p", "qwen-image-plus", MediaProtocol.DASHSCOPE_IMAGE)
        val request = MediaGenerationRequest(MediaKind.IMAGE, "Image", width = 0, height = 0, durationSeconds = 0)
        assertEquals(1328, mediaRequestDefaults(selection, request).width)
        assertEquals(1024, mediaRequestDefaults(selection.copy(modelId = "qwen-image-3.0"), request).width)
        val video = mediaRequestDefaults(selection.copy(modelId = "wan2.7-t2v", protocol = MediaProtocol.DASHSCOPE_VIDEO),
            request.copy(kind = MediaKind.VIDEO))
        assertEquals(1280, video.width)
        assertEquals(720, video.height)
        assertEquals(2, video.durationSeconds)
    }

    @Test fun boundedWaitRetainsKnownJobForRecoveryAndDoesNotRepeatSubmission() = runTest {
        val f = Fixture(backgroundScope, timeoutMillis = 5)
        f.verify()
        f.gateway.submitAction = { MediaSubmission.Accepted("long-job") }
        f.gateway.pollAction = { MediaPollResult.Pending }
        assertFailsWith<IllegalStateException> { f.service.generate(f.owner, "long", f.request) }
        assertEquals(MediaPhase.UNKNOWN, f.service.operations.value.getValue("tool-call").phase)
        f.gateway.pollAction = { MediaPollResult.Completed(MediaRemoteOutput(MediaKind.IMAGE, url = "https://media.example/ready")) }
        assertEquals(MediaPhase.READY, f.service.recover("long")?.phase)
        assertEquals(2, f.gateway.submissions)
    }

    @Test fun incompatibleSelectionCannotReuseImageVerificationForVideoOrSubscription() = runTest {
        val f = Fixture(backgroundScope)
        f.verify()
        f.settings.value = f.settings.value.copy(media = f.settings.value.media.copy(video = f.selection))
        assertFalse(f.service.available(MediaKind.VIDEO))
        assertEquals(MediaAvailability.UNAVAILABLE, f.service.check(MediaKind.VIDEO, f.selection, f.profile).availability)
        f.profiles.value = listOf(f.profile.copy(provider = ProviderType.OPENAI_SUBSCRIPTION))
        assertFalse(f.service.available(MediaKind.IMAGE))
        assertEquals(1, f.gateway.submissions)
    }

    @Test fun cancellationSurvivesFailureToSaveItsOutcome() = runTest {
        val f = Fixture(backgroundScope)
        f.verify()
        f.gateway.submitAction = { MediaSubmission.Accepted("provider-job") }
        f.gateway.pollAction = { awaitCancellation() }
        val attempt = launch { f.service.generate(f.owner, "cancel", f.request) }
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        f.journal.failWrites = true
        f.service.prepareForReset()
        attempt.join()
        assertTrue(attempt.isCancelled)
        assertEquals(MediaPhase.UNKNOWN, f.service.operations.value.getValue("tool-call").phase)
    }

    @Test fun revokedPolicyIsRecheckedAfterDurableIntentImmediatelyBeforeSubmission() = runTest {
        var permitted = true
        val f = Fixture(backgroundScope, authorizeSubmission = { _, _ -> permitted })
        f.verify()
        f.journal.afterAppend = { if ("\"type\":\"Submit\"" in it) permitted = false }
        assertFailsWith<IllegalStateException> { f.service.generate(f.owner, "revoked", f.request) }
        assertEquals(1, f.gateway.submissions)
        assertEquals(MediaPhase.FAILED, f.service.operations.value.getValue("tool-call").phase)
    }

    @Test fun unknownPollStateNeverBecomesConfirmedFailure() = runTest {
        val f = Fixture(backgroundScope)
        f.verify()
        f.gateway.submitAction = { MediaSubmission.Accepted("provider-job") }
        f.gateway.pollAction = { MediaPollResult.Failed(MediaGatewayException(MediaFailureKind.UNKNOWN_OUTCOME, "Unknown task state")) }
        assertFailsWith<MediaGatewayException> { f.service.generate(f.owner, "uncertain-poll", f.request) }
        assertEquals(MediaPhase.UNKNOWN, f.service.operations.value.getValue("tool-call").phase)
        assertTrue(f.service.available(MediaKind.IMAGE))
        assertEquals(2, f.gateway.submissions)
    }

    @Test fun cancelledOlderProbeCannotOverwriteAChangedConnectionCheck() = runTest {
        val f = Fixture(backgroundScope)
        val firstOutput = CompletableDeferred<MediaSubmission>()
        f.gateway.submitAction = { firstOutput.await() }
        val oldCheck = launch { f.verify() }
        runCurrent()
        f.profiles.value = listOf(f.profile.copy(apiKey = "changed-key"))
        f.gateway.submitAction = { MediaSubmission.Completed(MediaRemoteOutput(MediaKind.IMAGE, url = "https://media.example/new")) }
        f.verify()
        val newest = f.service.state.value[MediaKind.IMAGE]
        oldCheck.cancelAndJoin()
        assertEquals(newest, f.service.state.value[MediaKind.IMAGE])
        firstOutput.complete(MediaSubmission.Completed(MediaRemoteOutput(MediaKind.IMAGE, url = "https://media.example/old")))
        runCurrent()
        assertEquals(newest, f.service.state.value[MediaKind.IMAGE])
    }
}
