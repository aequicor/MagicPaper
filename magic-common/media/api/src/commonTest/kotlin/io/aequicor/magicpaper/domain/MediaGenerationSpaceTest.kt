package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.MediaGenerationMachine.Fact
import io.aequicor.magicpaper.domain.MediaGenerationMachine.Intent
import io.aequicor.magicpaper.machine.verifyStateSpace
import kotlin.test.Test

/**
 * The representatives of [MediaGenerationSpace], kept here rather than in the api so a shipped
 * binary — the browser bundle included — carries no fixtures.
 *
 * Each one is built by running the machine from `initial`, never by constructing a state, which is
 * what the `internal constructor` on `State` is there to enforce.
 */
class MediaGenerationSpaceTest {
    private val operation = MediaOperationData("operation", MediaGenerationOwner("session", "request", "call"),
        MediaGenerationRequest(MediaKind.IMAGE, ""), MediaModelSelection("profile", "model"), "connection",
        GeneratedMedia("call", MediaKind.IMAGE), requestFingerprint = "request-fingerprint", createdAt = 1)
    private val output = MediaRemoteOutput(MediaKind.IMAGE, url = "https://example.invalid/image")
    private val asset = MediaAsset("hash", "image/png", 12, 1280, 720)
    private fun step(state: MediaGenerationMachine.State, input: MediaGenerationMachine.Input) = MediaGenerationMachine.reduce(state, input).state

    private val new = MediaGenerationMachine.initial()
    private val created = step(new, Intent.Create(operation))
    private val submitting = step(created, Intent.Submit)
    private val waiting = step(submitting, Fact.Accepted("job"))
    private val polling = step(waiting, Intent.Poll)
    private val outputAvailable = step(polling, Fact.Output(output))
    private val downloading = step(outputAvailable, Intent.Download())
    private val ready = step(downloading, Fact.Asset(asset))

    @Test fun declaredSpaceIsClosedAndMatchesEveryTransition() = verifyStateSpace(
        MediaGenerationMachine,
        states = mapOf(
            MediaGenerationSpace.NEW to new,
            MediaGenerationSpace.CREATED to created,
            MediaGenerationSpace.SUBMITTING to submitting,
            MediaGenerationSpace.WAITING to waiting,
            MediaGenerationSpace.POLLING to polling,
            MediaGenerationSpace.OUTPUT_AVAILABLE to outputAvailable,
            MediaGenerationSpace.DOWNLOADING to downloading,
            MediaGenerationSpace.READY to ready,
            MediaGenerationSpace.FAILED to step(submitting, Fact.Failure("Rejected", confirmed = true)),
            // The three ways an outcome stays unconfirmed, each with a different thing left to do.
            MediaGenerationSpace.UNKNOWN_SUBMIT to step(submitting, Fact.Failure("Unknown", confirmed = false)),
            MediaGenerationSpace.UNKNOWN_JOB to step(polling, Fact.Failure("Poll unavailable", confirmed = false)),
            MediaGenerationSpace.UNKNOWN_OUTPUT to step(downloading, Fact.Failure("Download interrupted", confirmed = false)),
            MediaGenerationSpace.DELETED to step(ready, Intent.Delete),
            MediaGenerationSpace.PERSISTENCE_UNKNOWN to step(waiting, Fact.PersistenceUnknown),
        ),
        inputs = mapOf(
            MediaGenerationSpace.CREATE to Intent.Create(operation),
            MediaGenerationSpace.SUBMIT to Intent.Submit,
            MediaGenerationSpace.POLL to Intent.Poll,
            MediaGenerationSpace.DOWNLOAD to Intent.Download(),
            MediaGenerationSpace.DELETE to Intent.Delete,
            MediaGenerationSpace.IMPORT to Fact.Import(operation),
            MediaGenerationSpace.ACCEPTED to Fact.Accepted("job"),
            MediaGenerationSpace.PENDING to Fact.Pending,
            MediaGenerationSpace.OUTPUT to Fact.Output(output),
            MediaGenerationSpace.ASSET to Fact.Asset(asset),
            MediaGenerationSpace.FAILURE_CONFIRMED to Fact.Failure("Rejected", confirmed = true),
            MediaGenerationSpace.FAILURE_UNCONFIRMED to Fact.Failure("Unknown", confirmed = false),
            MediaGenerationSpace.RESTORED to Fact.Restored,
            MediaGenerationSpace.PERSISTENCE_UNKNOWN_FACT to Fact.PersistenceUnknown,
        ),
    )
}
