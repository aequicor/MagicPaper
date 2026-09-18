package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MediaSettingsPersistenceTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val profile = LlmProfile("media-profile", "Media", "https://api.example/v1", "fixture-secret", modelId = "chat-model")
    private val image = MediaModelSelection(profile.id, "image-one", MediaProtocol.OPENAI_IMAGES, "https://media.example/v1")
    private val video = MediaModelSelection(profile.id, "wan2.7-t2v", MediaProtocol.DASHSCOPE_VIDEO, "https://video.example/api/v1")

    @Test fun savingSelectionPreservesOverviewDraftAndOtherMediaChoice() = runTest {
        withFixture { fixture ->
            fixture.service.saveMediaSelection(MediaKind.VIDEO, video)
            runCurrent()
            val form = fixture.service.drafts.settings(fixture.service.state.value.settings)
            form.update { it.copy(settings = it.settings!!.copy(queritApiKey = "unsaved-search-key")) }
            form.awaitSaved()
            val before = fixture.drafts.load(SettingsDrafts.SETTINGS)
            fixture.service.saveMediaSelection(MediaKind.IMAGE, image)
            runCurrent()
            assertEquals(image, fixture.settings.load().media.image)
            assertEquals(video, fixture.settings.load().media.video)
            assertEquals("", fixture.settings.load().queritApiKey)
            assertEquals(before, fixture.drafts.load(SettingsDrafts.SETTINGS))
            assertTrue(fixture.media.checks.isEmpty(), "Saving/restoring configuration must not send a probe")

            fixture.service.saveOverviewSettings(form.state.value.value.settings!!)
            runCurrent()
            assertEquals(MediaSettings(image, video), fixture.settings.load().media)
            assertEquals("unsaved-search-key", fixture.settings.load().queritApiKey)
            fixture.service.saveMediaSelection(MediaKind.IMAGE, null)
            runCurrent()
            assertNull(fixture.settings.load().media.image)
            assertEquals(video, fixture.settings.load().media.video)
        }
    }

    @Test fun explicitProbeUsesSavedSelectionAndProfileAndExposesServiceStatus() = runTest {
        withFixture { fixture ->
            fixture.media.onCheck = { kind, selected, suppliedProfile ->
                assertEquals(image, fixture.settings.load().media.image, "Selection must be saved before probe starts")
                assertEquals(MediaKind.IMAGE, kind)
                assertEquals(image, selected)
                assertEquals(profile.id, suppliedProfile.id)
                assertEquals(profile.apiKey, suppliedProfile.apiKey)
            }
            fixture.service.saveMediaSelection(MediaKind.IMAGE, image, verify = true)
            runCurrent()
            assertEquals(1, fixture.media.checks.size)
            assertTrue(fixture.service.state.value.mediaSupported)
            assertEquals(MediaAvailability.AVAILABLE, fixture.service.state.value.mediaConnections[MediaKind.IMAGE]?.availability)
            assertFalse(fixture.service.state.value.settingsSaving)
        }
    }

    @Test fun failedSaveRetainsInputAndCannotStartAProbe() = runTest {
        withFixture { fixture ->
            val form = fixture.service.drafts.media(MediaKind.IMAGE, null)
            form.update { it.copy(fields = it.fields + ("model" to image.modelId)) }
            form.awaitSaved()
            val before = fixture.drafts.load(SettingsDrafts.mediaKey(MediaKind.IMAGE))
            fixture.settings.failSaves = true
            fixture.service.saveMediaSelection(MediaKind.IMAGE, image, verify = true)
            runCurrent()
            assertNull(fixture.service.state.value.settings.media.image)
            assertNull(fixture.settings.load().media.image)
            assertTrue(fixture.media.checks.isEmpty())
            assertEquals(before, fixture.drafts.load(SettingsDrafts.mediaKey(MediaKind.IMAGE)))
            assertFalse(fixture.service.state.value.settingsSaving)
            assertTrue(fixture.service.state.value.notice.orEmpty().startsWith("Не удалось сохранить"))
            assertFalse(fixture.service.state.value.notice.orEmpty().contains("private"))
        }
    }

    @Test fun delayedProbeCompletionCannotRestoreReplacedSelection() = runTest {
        withFixture { fixture ->
            val completion = CompletableDeferred<MediaConnectionStatus>()
            fixture.media.delayedCheck = completion
            fixture.service.saveMediaSelection(MediaKind.IMAGE, image, verify = true)
            runCurrent()
            assertEquals(1, fixture.media.checks.size)
            assertFalse(fixture.service.state.value.settingsSaving, "An active probe must not lock other settings")
            val replacement = image.copy(modelId = "image-two")
            fixture.service.saveMediaSelection(MediaKind.IMAGE, replacement)
            runCurrent()
            assertEquals(replacement, fixture.settings.load().media.image)
            // The media owner already discarded stale publication. Its return value is still an
            // old operation result, which Settings must not copy back into current configuration.
            completion.complete(MediaConnectionStatus(MediaKind.IMAGE, MediaAvailability.AVAILABLE, image.modelId))
            runCurrent()
            assertEquals(replacement, fixture.service.state.value.settings.media.image)
            assertEquals(replacement.modelId, fixture.service.state.value.mediaConnections[MediaKind.IMAGE]?.fingerprint)
            assertEquals(MediaAvailability.UNCHECKED, fixture.service.state.value.mediaConnections[MediaKind.IMAGE]?.availability)
        }
    }

    @Test fun profileArchiveRestoresInlineImagesAndVideoIntoFreshStorageWithoutGeneration() = runTest {
        withFixture { source ->
            source.service.saveMediaSelection(MediaKind.IMAGE, image)
            runCurrent()
            source.service.saveMediaSelection(MediaKind.VIDEO, video)
            runCurrent()
            val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
            val mp4 = byteArrayOf(0, 0, 0, 16) + "ftypisom".encodeToByteArray()
            val imageAsset = source.assets.put(png, "image/png", 1024, 1024)
            val videoAsset = source.assets.put(mp4, "video/mp4", 1280, 720, 2.0)
            val content = listOf(
                TranscriptBlock.Markdown("intro", "Introduction"),
                TranscriptBlock.Media("image-block", GeneratedMedia("image-block", MediaKind.IMAGE, MediaPhase.READY, "Diagram", asset = imageAsset)),
                TranscriptBlock.Markdown("explanation", "Explanation"),
                TranscriptBlock.Media("video-block", GeneratedMedia("video-block", MediaKind.VIDEO, MediaPhase.READY, "Animation", asset = videoAsset)),
            )
            val session = ChatSession("research", "Research", 1, 2,
                messages = listOf(ChatMessage("answer", ChatRole.AGENT, "Introduction\nExplanation", 2, content = content)),
                pendingContent = listOf(content[1]), mediaTools = SessionMediaTools(images = false, videos = true))
            source.chats.save(session)
            source.service.exportProfile()
            source.service.state.first { it.notice?.contains("экспорт", ignoreCase = true) == true }
            val exported = assertNotNull(source.bridge.exported)
            val bundle = json.decodeFromString(ProfileBundle.serializer(), exported)
            assertEquals(2, bundle.generatedAssets.size, "References repeated in pending content must not duplicate binary data")
            assertEquals(setOf(imageAsset.id, videoAsset.id), bundle.generatedAssets.map { it.asset.id }.toSet())

            val destination = Fixture()
            try {
                destination.service.start()
                assertTrue(destination.assets.assets.isEmpty())
                destination.bridge.imported = exported
                destination.service.importProfile()
                destination.service.state.first { !it.settingsSaving }
                assertEquals("Профиль импортирован.", destination.service.state.value.notice)
                val restored = assertNotNull(destination.chats.session(session.id))
                assertEquals(content, restored.messages.single().content)
                assertEquals(session.pendingContent, restored.pendingContent)
                assertEquals(session.mediaTools, restored.mediaTools)
                assertEquals(MediaSettings(image, video), destination.settings.load().media)
                assertContentEquals(png, destination.service.readMediaAsset(imageAsset))
                assertContentEquals(mp4, destination.service.readMediaAsset(videoAsset))
                assertTrue(destination.media.checks.isEmpty())
                assertEquals(0, destination.media.generationCalls)
                assertTrue(destination.assets.assets.keys.containsAll(listOf(imageAsset.id, videoAsset.id)))
            } finally { destination.service.close() }
        }
    }

    @Test fun incompleteArchiveDoesNotImportDanglingMediaReferencesOrShowSuccess() = runTest {
        withFixture { fixture ->
            val unknown = MediaAsset("a".repeat(64), "image/png", 8, 1024, 1024)
            val session = ChatSession("missing-media", "Missing", 1, 1, messages = listOf(
                ChatMessage("answer", ChatRole.AGENT, "", 1, content = listOf(TranscriptBlock.Media("image",
                    GeneratedMedia("image", MediaKind.IMAGE, MediaPhase.READY, asset = unknown))))))
            fixture.bridge.imported = json.encodeToString(ProfileBundle.serializer(), ProfileBundle(
                exportedAt = 1, settings = AppSettings(media = MediaSettings(image = image)), plugins = emptyList(), sessions = listOf(session)))
            fixture.service.importProfile()
            fixture.service.state.first { !it.settingsSaving }
            assertNull(fixture.chats.session(session.id))
            assertNull(fixture.settings.load().media.image)
            assertFalse(fixture.service.state.value.settingsSaving)
            assertTrue(fixture.service.state.value.notice.orEmpty().startsWith("Не удалось завершить импорт"))
            assertTrue(fixture.media.checks.isEmpty())
        }
    }

    @Test fun archiveDeduplicatesSameBytesWithDifferentDisplayMetadata() = runTest {
        withFixture { source ->
            val bytes = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
            val unknownSize = source.assets.put(bytes, "image/png")
            val knownSize = source.assets.put(bytes, "image/png", 1024, 1024)
            for ((id, asset) in listOf("old" to unknownSize, "new" to knownSize)) {
                source.chats.save(ChatSession(id, id, 1, 1, messages = listOf(
                    ChatMessage("answer-$id", ChatRole.AGENT, "", 1, content = listOf(TranscriptBlock.Media("media-$id",
                        GeneratedMedia("media-$id", MediaKind.IMAGE, MediaPhase.READY, asset = asset)))))))
            }
            source.service.exportProfile()
            source.service.state.first { it.notice?.contains("экспорт", ignoreCase = true) == true }
            val raw = assertNotNull(source.bridge.exported)
            assertEquals(1, json.decodeFromString(ProfileBundle.serializer(), raw).generatedAssets.size)
            val destination = Fixture()
            try {
                destination.service.start()
                destination.bridge.imported = raw
                destination.service.importProfile()
                destination.service.state.first { !it.settingsSaving }
                assertEquals("Профиль импортирован.", destination.service.state.value.notice)
                for ((id, asset) in listOf("old" to unknownSize, "new" to knownSize)) {
                    val restored = assertNotNull(destination.chats.session(id)).messages.single().content.single()
                    assertEquals(asset, assertIs<TranscriptBlock.Media>(restored).media.asset)
                    assertContentEquals(bytes, destination.assets.read(asset))
                }
            } finally { destination.service.close() }
        }
    }

    @Test fun corruptArchiveDigestCannotBeAttachedToImportedHistory() = runTest {
        withFixture { fixture ->
            val asset = MediaAsset("a".repeat(64), "image/png", 4, 1024, 1024)
            val session = ChatSession("tampered", "Tampered", 1, 1, messages = listOf(
                ChatMessage("answer", ChatRole.AGENT, "", 1, content = listOf(TranscriptBlock.Media("image",
                    GeneratedMedia("image", MediaKind.IMAGE, MediaPhase.READY, asset = asset))))))
            fixture.bridge.imported = json.encodeToString(ProfileBundle.serializer(), ProfileBundle(
                exportedAt = 1, settings = AppSettings(), plugins = emptyList(), sessions = listOf(session),
                generatedAssets = listOf(ExportedMediaAsset(asset, Base64.encode(byteArrayOf(1, 2, 3, 4))))))
            fixture.service.importProfile()
            fixture.service.state.first { !it.settingsSaving }
            assertNull(fixture.chats.session(session.id))
            assertFalse(fixture.service.state.value.notice.orEmpty() == "Профиль импортирован.")
            assertTrue(fixture.media.checks.isEmpty())
        }
    }

    private suspend fun TestScope.withFixture(block: suspend TestScope.(Fixture) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        try {
            fixture.profiles.save(profile)
            fixture.service.start()
            runCurrent()
            block(fixture)
        } finally { fixture.service.close(); Dispatchers.resetMain() }
    }

    private inner class Fixture {
        val store = InMemoryKeyValueStore()
        val settings = FailingSettingsRepository(JsonSettingsRepository(store, json))
        val profiles = JsonLlmProfileRepository(store, json)
        val chats = JsonChatRepository(store, json)
        val bridge = RecordingBridge()
        val drafts = InMemoryDraftRepository()
        val assets = MemoryMediaStore()
        val media = FakeMediaService(settings)
        val service = DefaultSettingsService(settings, profiles, chats, bridge, store, json,
            usage = TestUsageLedger(), draftRepository = drafts, mediaGeneration = media, mediaStore = assets)
    }

    private class FailingSettingsRepository(private val delegate: SettingsRepository) : SettingsRepository by delegate {
        var failSaves = false
        override suspend fun save(settings: AppSettings) {
            if (failSaves) error("private persistence details")
            delegate.save(settings)
        }
    }

    private class RecordingBridge : ProfileBridge {
        override val supportsFilePicker = true
        var exported: String? = null
        var imported: String? = null
        override suspend fun export(json: String): Boolean { exported = json; return true }
        override suspend fun import(): String? = imported
    }

    private class FakeMediaService(private val settings: SettingsRepository) : MediaGenerationService {
        override val state = MutableStateFlow<Map<MediaKind, MediaConnectionStatus>>(emptyMap())
        override val operations = MutableStateFlow<Map<String, GeneratedMedia>>(emptyMap())
        val checks = mutableListOf<MediaModelSelection>()
        var generationCalls = 0
        var onCheck: suspend (MediaKind, MediaModelSelection, LlmProfile) -> Unit = { _, _, _ -> }
        var delayedCheck: CompletableDeferred<MediaConnectionStatus>? = null
        override suspend fun refreshAvailability() {
            val saved = settings.load().media
            state.value = MediaKind.entries.associateWith { kind ->
                MediaConnectionStatus(kind, MediaAvailability.UNCHECKED, saved.selection(kind)?.modelId.orEmpty())
            }
        }
        override suspend fun check(kind: MediaKind, selection: MediaModelSelection, profile: LlmProfile): MediaConnectionStatus {
            checks += selection
            onCheck(kind, selection, profile)
            delayedCheck?.let { return it.await() }
            val result = MediaConnectionStatus(kind, MediaAvailability.AVAILABLE, selection.modelId)
            state.value += kind to result
            return result
        }
        override suspend fun available(kind: MediaKind) = state.value[kind]?.availability == MediaAvailability.AVAILABLE
        override suspend fun generate(owner: MediaGenerationOwner, operationId: String, request: MediaGenerationRequest,
                                      authorize: suspend () -> Unit,
                                      onUpdate: suspend (GeneratedMedia) -> Unit): GeneratedMedia {
            generationCalls++
            error("Settings restore must not start generation")
        }
        override suspend fun recover(operationId: String, onUpdate: suspend (GeneratedMedia) -> Unit): GeneratedMedia? = error("Unexpected recovery")
        override suspend fun recoverMedia(mediaId: String): GeneratedMedia? = error("Unexpected recovery")
        override suspend fun recoverPending() = error("Unexpected recovery")
        override suspend fun deleteSession(sessionId: String) = Unit
        override suspend fun read(asset: MediaAsset): ByteArray = error("Settings should use its media store")
        override suspend fun localPath(asset: MediaAsset): String? = error("Settings should use its media store")
        override suspend fun prepareForReset() = Unit
        override suspend fun resumeAfterReset() = Unit
        override suspend fun close() = Unit
    }

    private class MemoryMediaStore : MediaStore {
        override val available = true
        val assets = mutableMapOf<String, ByteArray>()
        private val records = mutableMapOf<String, String>()
        override suspend fun put(bytes: ByteArray, mimeType: String, width: Int, height: Int, durationSeconds: Double?): MediaAsset {
            val id = digest(bytes)
            assets[id] = bytes.copyOf()
            return MediaAsset(id, mimeType, bytes.size.toLong(), width, height, durationSeconds)
        }
        override suspend fun read(asset: MediaAsset): ByteArray = assets[asset.id]?.copyOf() ?: error("Missing media")
        override suspend fun localPath(asset: MediaAsset): String? = null
        override suspend fun fingerprint(value: String) = digest(value.encodeToByteArray())
        override suspend fun readRecord(key: String) = records[key]
        override suspend fun writeRecord(key: String, value: String) { records[key] = value }
        override suspend fun records(prefix: String) = records.filterKeys { it.startsWith(prefix) }
        override suspend fun deleteRecord(key: String) { records.remove(key) }
        override suspend fun deleteAsset(asset: MediaAsset) { assets.remove(asset.id) }
        override suspend fun clear() { assets.clear(); records.clear() }
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }

    private class TestUsageLedger : UsageLedger {
        override val state = MutableStateFlow(UsageArchive())
        override val failure = MutableStateFlow<String?>(null)
        override suspend fun record(record: UsageRecord, replacesId: String?) = error("Unexpected usage")
        override suspend fun context(snapshot: ContextUsageSnapshot) = error("Unexpected context")
        override suspend fun cumulative(key: String, fingerprint: String, total: TokenUsage, last: TokenUsage, record: UsageRecord) = error("Unexpected usage")
        override suspend fun replace(archive: UsageArchive) { state.value = archive }
        override suspend fun clear() { state.value = UsageArchive() }
        override suspend fun <T> measure(profile: LlmProfile, block: suspend () -> T): T = block()
    }
}
