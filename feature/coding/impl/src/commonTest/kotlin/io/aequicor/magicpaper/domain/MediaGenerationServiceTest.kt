package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.MediaStore
import io.aequicor.magicpaper.domain.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MediaGenerationServiceTest {
    private class Store : MediaStore {
        override val available = true
        val metadata = mutableMapOf<String, String>()
        var failWrites = false
        var afterWrite: (String) -> Unit = {}
        private val bytes = mutableMapOf<String, ByteArray>()
        override suspend fun put(bytes: ByteArray, mimeType: String, width: Int, height: Int, durationSeconds: Double?): MediaAsset {
            val id = fingerprint(bytes.joinToString(","))
            this.bytes[id] = bytes.copyOf()
            return MediaAsset(id, mimeType, bytes.size.toLong(), width, height, durationSeconds)
        }
        override suspend fun read(asset: MediaAsset) = bytes.getValue(asset.id).copyOf()
        override suspend fun localPath(asset: MediaAsset) = "/media/${asset.id}"
        override suspend fun fingerprint(value: String) = value.hashCode().toUInt().toString(16)
        override suspend fun readRecord(key: String) = metadata[key]
        override suspend fun writeRecord(key: String, value: String) {
            check(!failWrites) { "Disk unavailable" }
            metadata[key] = value
            afterWrite(value)
        }
        override suspend fun records(prefix: String) = metadata.filterKeys { it.startsWith(prefix) }
        override suspend fun deleteRecord(key: String) { metadata.remove(key) }
        override suspend fun deleteAsset(asset: MediaAsset) { bytes.remove(asset.id) }
        override suspend fun clear() { metadata.clear(); bytes.clear() }
    }

    private class Settings(var value: AppSettings) : SettingsRepository {
        override suspend fun load() = value
        override suspend fun save(settings: AppSettings) { value = settings }
        override suspend fun pluginStates() = emptyList<PluginState>()
        override suspend fun savePluginStates(states: List<PluginState>) = Unit
        override suspend fun wipe() { value = AppSettings() }
    }

    private class Profiles(var value: List<LlmProfile>) : LlmProfileRepository {
        override suspend fun load() = value
        override suspend fun save(profile: LlmProfile) { value = value.filterNot { it.id == profile.id } + profile }
        override suspend fun delete(id: String) { value = value.filterNot { it.id == id } }
        override suspend fun replaceAll(profiles: List<LlmProfile>) { value = profiles }
    }

    private class Gateway : MediaGenerationGateway {
        var submissions = 0
        var polls = 0
        var submitAction: suspend (MediaGenerationRequest) -> MediaSubmission = {
            MediaSubmission.Completed(MediaRemoteOutput(it.kind, url = "https://media.example/result", width = it.width, height = it.height))
        }
        var pollAction: suspend () -> MediaPollResult = { MediaPollResult.Completed(MediaRemoteOutput(MediaKind.VIDEO, url = "https://media.example/video")) }
        override suspend fun submit(profile: LlmProfile, selection: MediaModelSelection, request: MediaGenerationRequest): MediaSubmission {
            submissions++
            return submitAction(request)
        }
        override suspend fun poll(profile: LlmProfile, selection: MediaModelSelection, jobId: String, kind: MediaKind): MediaPollResult {
            polls++
            return pollAction()
        }
        override suspend fun download(output: MediaRemoteOutput) = DownloadedMedia(byteArrayOf(1, 2, 3), output.mimeType, output.width, output.height)
    }

    private class Fixture(scope: CoroutineScope, timeoutMillis: Long = 20 * 60 * 1_000) {
        val profile = LlmProfile("media", "Media", "https://media.example/v1", apiKey = "private-key", modelId = "chat")
        val selection = MediaModelSelection(profile.id, "image-model", baseUrl = profile.baseUrl)
        val settings = Settings(AppSettings(media = MediaSettings(image = selection)))
        val profiles = Profiles(listOf(profile))
        val store = Store()
        val gateway = Gateway()
        val usage = object : UsageLedger {
            override val state = MutableStateFlow(UsageArchive())
            override val failure = MutableStateFlow<String?>(null)
            override suspend fun record(record: UsageRecord, replacesId: String?) {
                state.value = state.value.copy(records = state.value.records.filterNot { it.id == (replacesId ?: record.id) } + record)
            }
            override suspend fun context(snapshot: ContextUsageSnapshot) = Unit
            override suspend fun cumulative(key: String, fingerprint: String, total: TokenUsage, last: TokenUsage, record: UsageRecord) = Unit
            override suspend fun replace(archive: UsageArchive) { state.value = archive }
            override suspend fun clear() { state.value = UsageArchive() }
            override suspend fun <T> measure(profile: LlmProfile, block: suspend () -> T): T = block()
        }
        val service = DefaultMediaGenerationService(settings, profiles, gateway, store, usage, scope, pollDelayMillis = 1,
            waitTimeoutMillis = timeoutMillis)
        val owner = MediaGenerationOwner("session", "request", "tool-call", "project")
        val request = MediaGenerationRequest(MediaKind.IMAGE, "A landscape")
        suspend fun verify() = service.check(MediaKind.IMAGE, selection, profiles.value.single())
    }

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
        f.profiles.save(f.profile.copy(apiKey = "replacement-key"))
        result.complete(MediaSubmission.Completed(MediaRemoteOutput(MediaKind.IMAGE, url = "https://media.example/image")))
        assertEquals(MediaAvailability.AVAILABLE, probe.await().availability)
        assertFalse(f.service.available(MediaKind.IMAGE))
        assertTrue(f.store.metadata.values.none { "private-key" in it || "replacement-key" in it })
    }

    @Test fun noAuthenticationLocalConnectionCanBeVerified() = runTest {
        val f = Fixture(backgroundScope)
        f.profiles.save(f.profile.copy(apiKey = "", authType = null))
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

    @Test fun confirmedPromptRejectionKeepsCapabilityWithoutUnknownToolOutcome() = runTest {
        val f = Fixture(backgroundScope)
        f.verify()
        val receipts = MemoryToolReceiptStore()
        val host = ToolHost(receipts).apply { mediaGeneration = f.service }
        val context = host.prepareMediaContext(ToolExecutionContext("project", "session", "session", "request",
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
            val host = ToolHost(receipts).apply {
                mediaGeneration = f.service
                unknownOutcome = { _, _ -> quarantines++ }
            }
            val context = host.prepareMediaContext(ToolExecutionContext("project", "session", "session", "request",
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
        val host = ToolHost(MemoryToolReceiptStore()).apply {
            mediaGeneration = f.service
            mediaAllowed = { _, _ -> enabled }
        }
        val context = ToolExecutionContext("project", "session", "session", "request", ToolRole.CHAT, CodingInteractionMode.RESEARCH)
        val allowed = host.prepareMediaContext(context, SessionMediaTools())
        assertEquals(setOf(MediaKind.IMAGE), allowed.mediaCapabilities)
        val tools = host.session(allowed)
        assertTrue(tools.definitions.any { it.id == "image.generate" })
        assertFalse(tools.definitions.any { it.id == "video.generate" })
        enabled = false
        assertFailsWith<IllegalStateException> { tools.call("blocked", "image.generate", buildJsonObject { put("prompt", "Image") }) }
        assertEquals(1, f.gateway.submissions)
        assertTrue(host.prepareMediaContext(context, SessionMediaTools(images = false)).mediaCapabilities.isEmpty())
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
        f.profiles.save(f.profile.copy(provider = ProviderType.OPENAI_SUBSCRIPTION))
        assertFalse(f.service.available(MediaKind.IMAGE))
        assertEquals(1, f.gateway.submissions)
    }

    @Test fun successfulRecoverySettlesOnlyItsExactUnknownToolReceipt() = runTest {
        val f = Fixture(backgroundScope)
        f.verify()
        val receipts = MemoryToolReceiptStore()
        val host = ToolHost(receipts).apply { mediaGeneration = f.service }
        f.service.onTerminal = host::reconcileMediaCompletion
        val context = host.prepareMediaContext(ToolExecutionContext("project", "session", "session", "request",
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
            host.reconcileMediaCompletion(f.owner.copy(callId = receipt.id, runtimeGeneration = 99), receipt.operationId, result)
        }
        assertEquals(2, f.gateway.submissions)
    }

    @Test fun startupSettlesLostReceiptAcknowledgementFromAlreadySavedAsset() = runTest {
        val f = Fixture(backgroundScope)
        f.verify()
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
        val host = ToolHost(receipts).apply { mediaGeneration = f.service }
        f.service.onTerminal = host::reconcileMediaCompletion
        val context = host.prepareMediaContext(ToolExecutionContext("project", "session", "session", "request",
            ToolRole.CHAT, CodingInteractionMode.CODE), SessionMediaTools())
        assertFailsWith<IllegalStateException> {
            host.session(context).call("media", "image.generate", buildJsonObject { put("prompt", "Image") })
        }
        assertEquals(ToolPhase.UNKNOWN, saved.get("project/session/request/media")?.phase)
        f.service.recoverPending()
        assertEquals(ToolPhase.SUCCEEDED, saved.get("project/session/request/media")?.phase)
        assertEquals(2, f.gateway.submissions)
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
        f.store.failWrites = true
        f.service.prepareForReset()
        attempt.join()
        assertTrue(attempt.isCancelled)
        assertEquals(MediaPhase.UNKNOWN, f.service.operations.value.getValue("tool-call").phase)
    }

    @Test fun revokedPolicyIsRecheckedAfterDurableIntentImmediatelyBeforeSubmission() = runTest {
        val f = Fixture(backgroundScope)
        f.verify()
        var permitted = true
        f.service.authorizeSubmission = { _, _ -> permitted }
        f.store.afterWrite = { if ("\"submitted\":true" in it) permitted = false }
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
        f.profiles.save(f.profile.copy(apiKey = "changed-key"))
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
