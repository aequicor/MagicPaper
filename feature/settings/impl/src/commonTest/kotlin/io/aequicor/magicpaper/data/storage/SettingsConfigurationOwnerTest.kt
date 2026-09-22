package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsConfigurationOwnerTest {
    @Test fun runtimePreparesBeforeConfigurationCommitAndAppliesOnlyTheCommittedValue() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.owner.start()
        val before = f.owner.settings()
        val next = before.copy(computerAccess = ComputerAccess.SCREEN, googleApiKey = "settings-test-secret")
        f.runtime.onPrepare = { previous, proposed ->
            assertEquals(before, previous)
            assertEquals(next, proposed)
            assertEquals(before, f.owner.state.value.settings)
            assertIs<SettingsRuntimePolicy.Unconfirmed>(f.owner.runtimePolicy())
            assertIs<SettingsMachine.Intent.ChangeSettings>(f.inputs().last())
        }
        f.runtime.onApply = { applied ->
            assertEquals(next, applied)
            assertEquals(next, f.owner.state.value.settings)
            assertIs<SettingsRuntimePolicy.Unconfirmed>(f.owner.runtimePolicy())
            assertIs<SettingsMachine.Fact.RuntimePrepared>(f.inputs().last())
        }
        assertTrue(f.owner.changeSettings(next).isSuccess)
        assertEquals(listOf("prepare", "apply"), f.runtime.calls)
        assertNull(f.owner.state.value.unfinishedChange)
        assertFalse(f.owner.state.value.unknown)
        assertEquals(next, assertIs<SettingsRuntimePolicy.Confirmed>(f.owner.runtimePolicy()).settings)
        f.assertNoPlaintext("settings-test-secret")
    }

    @Test fun committedBeginWithFailedCheckpointCanBeExplicitlyRetriedWithoutEarlyRuntimeWork() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.owner.start()
        val before = f.owner.settings()
        val next = before.copy(computerAccess = ComputerAccess.CONTROL)
        f.journal.afterAppend = { input -> if (input is SettingsMachine.Intent.ChangeSettings) {
            f.store.failSettingsWrite = StorageException("controlled checkpoint", StorageException.Kind.WRITE)
        } }
        assertFailsWith<StorageException> { f.owner.changeSettings(next) }
        assertEquals(before, f.owner.state.value.settings)
        assertTrue(f.owner.state.value.unknown)
        assertNotNull(f.owner.state.value.unfinishedChange)
        assertTrue(f.runtime.calls.isEmpty())
        f.journal.afterAppend = {}
        assertTrue(f.owner.retrySettingsChange().isSuccess)
        assertEquals(next, f.owner.settings())
        assertEquals(listOf("prepare", "apply"), f.runtime.calls)
    }

    @Test fun cancellationAfterBeginCommitPreservesCancellationAndLeavesAnExplicitRetry() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.owner.start()
        val cancelled = CancellationException("controlled begin cancellation")
        f.journal.failureAfterAppend = { if (it is SettingsMachine.Intent.ChangeSettings) cancelled else null }
        val next = f.owner.settings().copy(computerAccess = ComputerAccess.SCREEN)
        val failure = assertFailsWith<CancellationException> { f.owner.changeSettings(next) }
        assertTrue(failure.hasCause(cancelled))
        assertTrue(f.owner.state.value.unknown)
        assertTrue(f.runtime.calls.isEmpty())
        f.journal.failureAfterAppend = { null }
        assertTrue(f.owner.retrySettingsChange().isSuccess)
        assertEquals(listOf("prepare", "apply"), f.runtime.calls)
    }

    @Test fun cancellationIsPrimaryWhenLostReplyReadbackIsMalformed() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.owner.start()
        val cancelled = CancellationException("controlled append cancellation")
        f.journal.afterAppend = { if (it is SettingsMachine.Intent.ChangeSettings) f.journal.corruptRevision = true }
        f.journal.failureAfterAppend = { if (it is SettingsMachine.Intent.ChangeSettings) cancelled else null }
        val failure = assertFailsWith<CancellationException> {
            f.owner.changeSettings(f.owner.settings().copy(computerAccess = ComputerAccess.SCREEN))
        }
        assertTrue(failure.hasCause(cancelled))
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it.suppressedExceptions.isNotEmpty() },
            "The invalid readback must remain diagnosable without replacing cancellation")
        assertTrue(f.owner.state.value.unknown)
        assertTrue(f.runtime.calls.isEmpty())
    }

    @Test fun failedPrepareDoesNotCommitNewSettingsAndRestoreDoesNotRetryIt() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.owner.start()
        val before = f.owner.settings()
        val next = before.copy(computerAccess = ComputerAccess.CONTROL, googleApiKey = "pending-test-secret")
        f.runtime.onPrepare = { _, _ -> error("controlled prepare failure") }
        assertFailsWith<IllegalStateException> { f.owner.changeSettings(next) }
        assertEquals(before, f.owner.settings())
        assertEquals(listOf("prepare"), f.runtime.calls)
        f.owner = f.open()
        f.owner.start()
        assertEquals(listOf("prepare"), f.runtime.calls, "Restore cannot grant runtime authority")
        assertTrue(f.owner.state.value.unknown)
        f.runtime.onPrepare = { _, _ -> }
        assertIs<SettingsRuntimePolicy.Unconfirmed>(f.owner.runtimePolicy())
        assertTrue(f.owner.retrySettingsChange().isSuccess)
        assertEquals(next, f.owner.settings(), "Pending credentials survive until explicit application")
        assertEquals(listOf("prepare", "prepare", "apply"), f.runtime.calls)
        f.assertNoPlaintext("pending-test-secret")
    }

    @Test fun failedApplyKeepsCommittedSettingsAndRetryDoesNotRepeatPrepare() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.owner.start()
        val next = f.owner.settings().copy(applicationAccess = ComputerAccess.SCREEN)
        f.runtime.onApply = { error("controlled apply failure") }
        assertTrue(f.owner.changeSettings(next).isFailure)
        assertEquals(next, f.owner.settings())
        assertTrue(f.owner.state.value.unknown)
        f.owner = f.open(); f.owner.start()
        assertEquals(listOf("prepare", "apply"), f.runtime.calls)
        f.runtime.onApply = {}
        assertTrue(f.owner.retrySettingsChange().isSuccess)
        assertEquals(listOf("prepare", "apply", "apply"), f.runtime.calls)
        assertNull(f.owner.state.value.unfinishedChange)
    }

    @Test fun appliedCommitWithFailedCacheRemainsKnownAndDoesNotRepeatRuntimeWorkOnReopen() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.owner.start()
        val next = f.owner.settings().copy(applicationAccess = ComputerAccess.CONTROL)
        f.journal.afterAppend = { if (it is SettingsMachine.Fact.RuntimeApplied) {
            f.store.failSettingsWrite = StorageException("controlled applied checkpoint", StorageException.Kind.WRITE)
        } }
        assertTrue(f.owner.changeSettings(next).isFailure)
        assertEquals(next, f.owner.state.value.settings)
        assertFalse(f.owner.state.value.unknown)
        assertNull(f.owner.state.value.unfinishedChange)
        f.owner = f.open(); f.owner.start()
        assertEquals(next, f.owner.settings())
        assertEquals(next, assertIs<SettingsRuntimePolicy.Confirmed>(f.owner.runtimePolicy()).settings)
        assertEquals(listOf("prepare", "apply"), f.runtime.calls)
        assertFails { f.owner.retrySettingsChange() }
    }

    @Test fun committedProfileDeletionSurvivesSecretCleanupFailureAndReopenRetriesOnlyCleanup() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.owner.start()
        f.owner.saveProfile(profile(apiKey = "profile-test-secret"), null)
        val reference = f.secrets.values.keys.single()
        f.secrets.failDelete = true
        val failure = assertFailsWith<StorageException> {
            f.owner.deleteProfile(f.owner.state.value.profileRefs.getValue("provider"))
        }
        assertTrue(failure.committed)
        assertTrue(f.owner.state.value.profiles.isEmpty())
        assertEquals("profile-test-secret", f.secrets.values[reference])
        f.secrets.failDelete = false
        f.owner = f.open(); f.owner.start()
        assertTrue(f.owner.profiles().isEmpty())
        assertNull(f.secrets.values[reference])
        assertTrue(f.runtime.calls.isEmpty())
        f.assertNoPlaintext("profile-test-secret")
    }

    @Test fun lateCatalogCannotOverwriteADeletedAndRecreatedProfileOrItsCredential() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.owner.start()
        f.owner.saveProfile(profile(apiKey = "old-test-secret"), null)
        val ref = f.owner.state.value.profileRefs.getValue("provider")
        val loading = async { f.owner.refreshCatalog(ref, SettingsCatalogKind.MODELS) }
        runCurrent(); f.directory.started.await()
        f.owner.deleteProfile(ref)
        f.owner.saveProfile(profile(name = "New", apiKey = "new-test-secret"), null)
        f.directory.result.complete(models("late"))
        val refresh = loading.await()
        assertFalse(refresh.applied)
        assertTrue(refresh.models.isEmpty())
        val saved = f.owner.profiles().single()
        assertEquals("New", saved.name)
        assertEquals("new-test-secret", saved.apiKey)
        assertTrue(saved.modelCatalog.isEmpty())
        assertEquals(setOf("new-test-secret"), f.secrets.values.values.toSet())
        f.assertNoPlaintext("old-test-secret", "new-test-secret")
    }

    @Test fun reloadInterruptsCatalogWithoutRepeatingTheCallOrAcceptingItsLateResult() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.owner.start()
        f.owner.saveProfile(profile(), null)
        val loading = async { f.owner.refreshCatalog(f.owner.state.value.profileRefs.getValue("provider"), SettingsCatalogKind.MODELS) }
        runCurrent(); f.directory.started.await()
        f.owner.reload()
        assertEquals(1, f.directory.calls)
        assertEquals(1, f.owner.state.value.interruptedRequests.size)
        f.directory.result.complete(models("late"))
        val refresh = loading.await()
        assertFalse(refresh.applied)
        assertTrue(refresh.models.isEmpty())
        assertTrue(f.owner.profiles().single().modelCatalog.isEmpty())
        assertTrue(f.owner.state.value.interruptedRequests.isEmpty())
    }

    @Test fun legacyDossierWithBlankIdIsDroppedInsteadOfBlockingRestore() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val valid = ModelDossier("valid-id", "provider", "model-a", strengths = "ok")
        // Written by an older build, before the machine required a non-blank dossier id.
        val corrupt = ModelDossier("", "provider", "model-b", strengths = "legacy heuristic fallback")
        f.store.write("model-dossiers", f.json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ModelDossier.serializer()), listOf(valid, corrupt)))
        f.owner.start()
        assertEquals(listOf(valid), f.owner.dossiers())
    }

    @Test fun lateResearchCannotReplaceAManualDossierEdit() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.owner.start()
        f.owner.saveProfile(profile(), null)
        val captured = f.owner.state.value
        val ref = captured.profileRefs.getValue("provider")
        val request = SettingsDescriptionRequest("description", ref, ref, "model", captured.settingsVersion, null,
            judgeModelId = "judge-model")
        val researching = async { f.owner.researchDescription(request) {} }
        runCurrent(); f.researcher.started.await()
        assertEquals("judge-model", f.researcher.judge?.modelId)
        val manual = ModelDossier("manual", "provider", "model", strengths = "Manual decision")
        f.owner.saveDossier(manual, ref, null)
        f.researcher.result.complete(ModelDossier("generated", "provider", "model", strengths = "Late result", source = DossierSource.WEB))
        assertFalse(researching.await())
        assertEquals(listOf(manual), f.owner.dossiers())
    }

    @Test fun queuedProfileSaveFreezesCallerCollectionsBeforeWaitingForTheOwner() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.owner.start()
        f.owner.saveProfile(profile(apiKey = "existing-test-secret"), null)
        val ref = f.owner.state.value.profileRefs.getValue("provider")
        val reading = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        f.secrets.beforeRead = { reading.complete(Unit); resume.await() }
        val reader = async { f.owner.profiles() }
        runCurrent(); reading.await()
        val favorites = mutableListOf("captured-model")
        val saving = async(start = CoroutineStart.UNDISPATCHED) {
            f.owner.saveProfile(profile().copy(favoriteModels = favorites), ref)
        }
        favorites[0] = "changed-after-submission"
        f.secrets.beforeRead = {}
        resume.complete(Unit)
        reader.await(); saving.await()
        assertEquals(listOf("captured-model"), f.owner.profiles().single().favoriteModels)
        f.owner = f.open(); f.owner.start()
        assertEquals(listOf("captured-model"), f.owner.profiles().single().favoriteModels)
    }

    private fun profile(name: String = "Provider", apiKey: String = "") = LlmProfile("provider", name,
        baseUrl = "https://provider.invalid/v1", apiKey = apiKey, modelId = "model", modelLibraryVersion = 1)

    private fun models(id: String) = listOf(ModelDefaults.DiscoveredModel(id,
        recommendation = ModelDefaults.ModelRecommendation(EffortSelection.Default, AdvancedLlmOptions())))

    private fun Throwable.hasCause(expected: Throwable) = generateSequence(this) { it.cause }.any { it === expected }

    private class Runtime : SettingsRuntimeParticipant {
        val calls = mutableListOf<String>()
        var onPrepare: suspend (AppSettings, AppSettings) -> Unit = { _, _ -> }
        var onApply: suspend (AppSettings) -> Unit = {}
        override suspend fun prepare(previous: AppSettings, next: AppSettings) { calls += "prepare"; onPrepare(previous, next) }
        override suspend fun apply(settings: AppSettings) { calls += "apply"; onApply(settings) }
    }

    private class Directory : ModelDirectory {
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<List<ModelDefaults.DiscoveredModel>>()
        var calls = 0
        override suspend fun models(profile: LlmProfile): List<ModelDefaults.DiscoveredModel> {
            calls++; started.complete(Unit); return result.await()
        }
    }

    private class Researcher : DossierResearcher {
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<ModelDossier>()
        var judge: LlmProfile? = null
        override suspend fun research(target: LlmProfile, profile: LlmProfile?, settings: AppSettings, onProgress: (String) -> Unit): ModelDossier {
            judge = profile; started.complete(Unit); return result.await()
        }
    }

    private class Secrets : SecretStore {
        val values = mutableMapOf<String, String>()
        var failDelete = false
        var beforeRead: suspend () -> Unit = {}
        override suspend fun read(reference: String): String? { beforeRead(); return values[reference] }
        override suspend fun write(reference: String, value: String) { values[reference] = value }
        override suspend fun delete(reference: String) {
            if (failDelete) throw StorageException("controlled secret cleanup", StorageException.Kind.WRITE)
            values.remove(reference)
        }
    }

    private class Store(private val backing: KeyValueStore = InMemoryKeyValueStore()) : KeyValueStore by backing {
        var failSettingsWrite: Throwable? = null
        override fun read(key: String) = backing.read(key)
        override fun keys(prefix: String) = backing.keys(prefix)
        override fun delete(key: String) = backing.delete(key)
        override fun clear() = backing.clear()
        override fun write(key: String, value: String) {
            if (key == "settings") failSettingsWrite?.let { failSettingsWrite = null; throw it }
            backing.write(key, value)
        }
    }

    private class Fixture(dispatcher: CoroutineDispatcher) {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val store = Store().apply {
            write("settings", json.encodeToString(AppSettings.serializer(), AppSettings(llmBaseUrl = "", llmModel = "")))
        }
        val secrets = Secrets()
        val journal = Journal(store, json)
        val runtime = Runtime()
        val directory = Directory()
        val researcher = Researcher()
        private val execution = dispatcher
        var owner = open()
        fun open() = DefaultSettingsConfiguration(store, journal, secrets, json, directory = directory,
            researcher = researcher, runtime = runtime, dispatcher = execution)
        suspend fun inputs() = journal.delegate.read(SettingsInputJournal.STREAM).map { journal.input(it.detail) }
        fun assertNoPlaintext(vararg secrets: String) {
            for (key in store.keys("")) for (secret in secrets) assertFalse(checkNotNull(store.read(key)).contains(secret), key)
        }
    }

    private class Journal(private val store: KeyValueStore, private val json: Json,
        val delegate: EventJournal = InMemoryEventJournal()) : EventJournal by delegate {
        var afterAppend: (SettingsMachine.Input) -> Unit = {}
        var failureAfterAppend: (SettingsMachine.Input) -> Throwable? = { null }
        var corruptRevision = false
        fun input(detail: String): SettingsMachine.Input {
            val id = json.parseToJsonElement(detail).jsonObject.getValue("id").jsonPrimitive.content
            val payload = json.parseToJsonElement(checkNotNull(store.read(SettingsInputJournal.PREFIX + id))).jsonObject
            return json.decodeFromJsonElement(SettingsMachine.Input.serializer(), payload.getValue("input"))
        }
        override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
            val saved = delegate.append(expected, operation, at, detail) ?: return null
            val input = input(detail)
            afterAppend(input)
            failureAfterAppend(input)?.let { throw it }
            return saved
        }
        override suspend fun snapshot(stream: String): JournalSnapshot = delegate.snapshot(stream).let {
            if (corruptRevision) it.copy(revision = it.revision.copy(seq = it.revision.seq + 1)) else it
        }
    }
}
