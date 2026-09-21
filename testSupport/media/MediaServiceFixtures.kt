package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
internal object MediaServiceFixtures {
    class Journal(private val backing: EventJournal = InMemoryEventJournal()) : EventJournal by backing {
        var failWrites = false
        var afterAppend: (String) -> Unit = {}
        override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
            check(!failWrites) { "Journal unavailable" }
            return backing.append(expected, operation, at, detail).also { if (it != null) afterAppend(detail) }
        }
    }
    class Store : MediaStore {
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

    class Settings(var value: AppSettings) : SettingsRepository {
        override suspend fun load() = value
    }

    class Profiles(var value: List<LlmProfile>) : LlmProfileRepository {
        override suspend fun load() = value
    }

    class Gateway : MediaGenerationGateway {
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

    class Fixture(scope: CoroutineScope, timeoutMillis: Long = 20 * 60 * 1_000,
        onTerminal: suspend (MediaGenerationOwner, String, GeneratedMedia) -> Unit = { _, _, _ -> },
        authorizeSubmission: suspend (MediaGenerationOwner, MediaKind) -> Boolean = { _, _ -> true },
    ) {
        val profile = LlmProfile("media", "Media", "https://media.example/v1", apiKey = "private-key", modelId = "chat")
        val selection = MediaModelSelection(profile.id, "image-model", baseUrl = profile.baseUrl)
        val settings = Settings(AppSettings(media = MediaSettings(image = selection)))
        val profiles = Profiles(listOf(profile))
        val store = Store()
        val journal = Journal()
        val gateway = Gateway()
        val usage = object : UsageLedger {
            override val state = MutableStateFlow(UsageArchive())
            override val failure = MutableStateFlow<String?>(null)
            override suspend fun start() = Unit
            override suspend fun captureObservation(): UsageObservation = UsageObservation.Captured("fixture")
            override suspend fun exportArchive() = state.value
            override suspend fun record(observation: UsageObservation, record: UsageRecord, replacesId: String?) {
                state.value = state.value.copy(records = state.value.records.filterNot { it.id == (replacesId ?: record.id) } + record)
            }
            override suspend fun context(observation: UsageObservation, snapshot: ContextUsageSnapshot) = Unit
            override suspend fun cumulative(observation: UsageObservation, key: String, fingerprint: String, total: TokenUsage, last: TokenUsage, record: UsageRecord) = Unit
            override suspend fun replace(archive: UsageArchive) { state.value = archive }
            override suspend fun clear() { state.value = UsageArchive() }
            override suspend fun <T> measure(profile: LlmProfile, block: suspend () -> T): T = block()
        }
        val service = DefaultMediaGenerationService(settings::load, profiles::load, gateway, store, journal, usage, scope, pollDelayMillis = 1,
            waitTimeoutMillis = timeoutMillis, onTerminal = onTerminal, authorizeSubmission = authorizeSubmission)
        val owner = MediaGenerationOwner("session", "request", "tool-call", "project")
        val request = MediaGenerationRequest(MediaKind.IMAGE, "A landscape")
        suspend fun verify() = service.check(MediaKind.IMAGE, selection, profiles.value.single())
    }

}
