package io.aequicor.magicpaper.domain

import kotlin.test.*

class MediaGenerationMachineTest {
    private val operation = MediaOperationData("operation", MediaGenerationOwner("session", "request", "call"),
        MediaGenerationRequest(MediaKind.IMAGE, ""), MediaModelSelection("profile", "model"), "connection",
        GeneratedMedia("call", MediaKind.IMAGE), requestFingerprint = "request-fingerprint", createdAt = 1)
    private val output = MediaRemoteOutput(MediaKind.IMAGE, url = "https://example.invalid/image")
    private val asset = MediaAsset("hash", "image/png", 12, 1280, 720)
    private fun created() = MediaGenerationMachine.reduce(MediaGenerationMachine.initial(), MediaGenerationMachine.Intent.Create(operation)).state
    private fun submitting() = MediaGenerationMachine.reduce(created(), MediaGenerationMachine.Intent.Submit).state
    private fun waiting() = MediaGenerationMachine.reduce(submitting(), MediaGenerationMachine.Fact.Accepted("job")).state
    private fun polling() = MediaGenerationMachine.reduce(waiting(), MediaGenerationMachine.Intent.Poll).state
    private fun output() = MediaGenerationMachine.reduce(polling(), MediaGenerationMachine.Fact.Output(output)).state
    private fun downloading() = MediaGenerationMachine.reduce(output(), MediaGenerationMachine.Intent.Download()).state
    private fun ready() = MediaGenerationMachine.reduce(downloading(), MediaGenerationMachine.Fact.Asset(asset)).state
    private fun reject(state: MediaGenerationMachine.State, input: MediaGenerationMachine.Input) {
        val result = MediaGenerationMachine.reduce(state, input)
        assertEquals(state, result.state)
        assertIs<MediaGenerationMachine.Effect.Reject>(result.effects.single())
    }

    @Test fun submitPollAndDownloadRequireExplicitIntentsAndHaveOneEffect() {
        assertEquals(listOf(MediaGenerationMachine.Effect.Submit(operation.id)),
            MediaGenerationMachine.reduce(created(), MediaGenerationMachine.Intent.Submit).effects)
        assertEquals(listOf(MediaGenerationMachine.Effect.Poll("job")),
            MediaGenerationMachine.reduce(waiting(), MediaGenerationMachine.Intent.Poll).effects)
        assertEquals(listOf(MediaGenerationMachine.Effect.Download(output)),
            MediaGenerationMachine.reduce(output(), MediaGenerationMachine.Intent.Download()).effects)
        assertEquals(MediaPhase.READY, ready().operation?.media?.phase)
        assertEquals(asset, ready().operation?.media?.asset)
    }

    @Test fun replayRestoresEveryStageWithoutAnyEffectAndCannotResubmit() {
        val samples = listOf(created(), submitting(), waiting(), polling(), output(), downloading(), ready())
        for (sample in samples) {
            val restored = MediaGenerationMachine.reduce(sample, MediaGenerationMachine.Fact.Restored)
            assertTrue(restored.effects.isEmpty())
            reject(restored.state, MediaGenerationMachine.Intent.Submit)
        }
        assertEquals(MediaGenerationMachine.Stage.UNKNOWN, MediaGenerationMachine.reduce(submitting(), MediaGenerationMachine.Fact.Restored).state.stage)
        assertEquals(MediaGenerationMachine.Stage.WAITING, MediaGenerationMachine.reduce(polling(), MediaGenerationMachine.Fact.Restored).state.stage)
    }

    @Test fun unknownSubmissionDominatesWhileKnownJobCanOnlyBeObserved() {
        val unknown = MediaGenerationMachine.reduce(submitting(), MediaGenerationMachine.Fact.Failure("Unknown", false)).state
        reject(unknown, MediaGenerationMachine.Intent.Submit)
        reject(unknown, MediaGenerationMachine.Intent.Poll)
        reject(unknown, MediaGenerationMachine.Fact.Failure("Assumed rejection", true))
        val known = MediaGenerationMachine.reduce(polling(), MediaGenerationMachine.Fact.Failure("Poll unavailable", false)).state
        assertIs<MediaGenerationMachine.Effect.Poll>(MediaGenerationMachine.reduce(known, MediaGenerationMachine.Intent.Poll).effects.single())
        reject(known, MediaGenerationMachine.Intent.Submit)
    }

    @Test fun unknownPersistenceBlocksEvenReadOnlyRecoveryUntilDurableStateIsReloaded() {
        val state = MediaGenerationMachine.reduce(waiting(), MediaGenerationMachine.Fact.PersistenceUnknown).state
        reject(state, MediaGenerationMachine.Intent.Poll)
        reject(state, MediaGenerationMachine.Intent.Submit)
        assertEquals(state, MediaGenerationMachine.reduce(state, MediaGenerationMachine.Fact.Restored).state)
    }

    @Test fun rawPromptAndInlineProviderBytesCannotEnterInputs() {
        reject(MediaGenerationMachine.initial(), MediaGenerationMachine.Intent.Create(operation.copy(request = operation.request.copy(prompt = "private"))))
        reject(submitting(), MediaGenerationMachine.Fact.Output(output.copy(dataBase64 = "private bytes")))
    }

    @Test fun inlineOutputLostOnRestartNeverCausesResubmissionOrAFakeDownload() {
        val inline = MediaGenerationMachine.reduce(submitting(), MediaGenerationMachine.Fact.Output(output.copy(url = ""))).state
        reject(inline, MediaGenerationMachine.Intent.Download())
        assertIs<MediaGenerationMachine.Effect.Download>(MediaGenerationMachine.reduce(inline, MediaGenerationMachine.Intent.Download(ephemeralOutput = true)).effects.single())
        val restored = MediaGenerationMachine.reduce(inline, MediaGenerationMachine.Fact.Restored).state
        reject(restored, MediaGenerationMachine.Intent.Submit)
        reject(restored, MediaGenerationMachine.Intent.Download())
        val knownJob = MediaGenerationMachine.reduce(polling(), MediaGenerationMachine.Fact.Output(output.copy(url = ""))).state
        val recoveredJob = MediaGenerationMachine.reduce(knownJob, MediaGenerationMachine.Fact.Restored).state
        assertNull(recoveredJob.operation?.output)
        assertIs<MediaGenerationMachine.Effect.Poll>(MediaGenerationMachine.reduce(recoveredJob, MediaGenerationMachine.Intent.Poll).effects.single())
    }

    @Test fun finalAndDeletedOperationsCannotBeOverwrittenByLateFacts() {
        reject(ready(), MediaGenerationMachine.Fact.Failure("late", true))
        reject(ready(), MediaGenerationMachine.Fact.Asset(asset.copy(id = "other")))
        val deleted = MediaGenerationMachine.reduce(ready(), MediaGenerationMachine.Intent.Delete).state
        assertNull(deleted.operation?.media?.asset)
        assertEquals(MediaGenerationMachine.Stage.DELETED, deleted.stage)
        reject(deleted, MediaGenerationMachine.Fact.Asset(asset))
    }
}
