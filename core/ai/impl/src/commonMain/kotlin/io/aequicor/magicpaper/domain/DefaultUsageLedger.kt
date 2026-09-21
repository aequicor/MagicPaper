package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.EventJournal
import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Application-owned accounting. Provider execution remains in the caller of measure. */
class DefaultUsageLedger(
    repository: UsageRepository,
    journal: EventJournal,
    payloads: KeyValueStore,
    json: Json,
    private val storageDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : UsageLedger {
    private val json = Json(json) { encodeDefaults = true }
    private val lock = Mutex()
    private val inputs = UsageInputJournal(repository, journal, payloads, json)
    private var attemptedRestore = false
    private val mutableFailure = MutableStateFlow<String?>(null)
    override val failure = mutableFailure.asStateFlow()
    private val mutableState = MutableStateFlow(UsageArchive(startedAt = 0))
    override val state = mutableState.asStateFlow()

    override suspend fun start() = lock.withLock { restore() }

    private suspend fun restore() {
        attemptedRestore = true
        try {
            withContext(storageDispatcher) { inputs.restore() }
            publish()
            mutableFailure.value = null
        } catch (failure: Exception) {
            inputs.markUnknown()
            report("restore", failure)
            if (failure is CancellationException) throw failure
        }
    }

    private suspend fun initialize() { if (!attemptedRestore) restore() }

    override suspend fun captureObservation(): UsageObservation = lock.withLock {
        initialize()
        if (!inputs.state.initialized || inputs.state.persistenceUnknown) UsageObservation.Unavailable
        else UsageObservation.Captured(inputs.state.generationId)
    }

    override suspend fun record(observation: UsageObservation, record: UsageRecord, replacesId: String?) =
        observe(observation) { UsageMachine.Fact.Recorded(it, record, replacesId, stamp()) }

    override suspend fun context(observation: UsageObservation, snapshot: ContextUsageSnapshot) =
        observe(observation) { UsageMachine.Fact.ContextObserved(it, snapshot, stamp()) }

    override suspend fun cumulative(observation: UsageObservation, key: String, fingerprint: String,
        total: TokenUsage, last: TokenUsage, record: UsageRecord) = observe(observation) {
        UsageMachine.Fact.CumulativeObserved(it, UsageMachine.CumulativeProof(key, fingerprint, total, last, record), stamp())
    }

    private suspend fun observe(observation: UsageObservation, input: (UsageObservation.Captured) -> UsageMachine.Input) = lock.withLock {
        initialize()
        if (observation !is UsageObservation.Captured) {
            if (mutableFailure.value == null) mutableFailure.value = unavailableMessage
            return@withLock
        }
        try {
            withContext(storageDispatcher) { inputs.commit(input(observation)) }
            publish()
            mutableFailure.value = null
        } catch (failure: Exception) {
            publish()
            report("observe", failure)
            if (failure is CancellationException) throw failure
        }
    }

    override suspend fun exportArchive(): UsageArchive = lock.withLock {
        initialize()
        try { withContext(storageDispatcher) { inputs.verifiedArchive() } }
        catch (failure: Exception) {
            report("export", failure)
            throw failure
        }
    }

    override suspend fun replace(archive: UsageArchive) = replace(UsageMachine.Intent.Import(archive.copy(
        records = archive.records.toList(), contexts = archive.contexts.toMap(), cursors = archive.cursors.toMap()), stamp()), reload = false)
    override suspend fun clear() = replace(UsageMachine.Intent.Clear(stamp()), reload = true)

    private suspend fun replace(input: UsageMachine.Intent, reload: Boolean) = lock.withLock {
        initialize()
        if (reload) restore() // Application reset replaced the journal's epoch; an old revision is never reused.
        try {
            withContext(storageDispatcher) { inputs.replace(input) }
            publish()
            mutableFailure.value = null
        } catch (failure: Exception) {
            publish()
            report("replace", failure)
            throw failure
        }
    }

    private fun publish() {
        if (inputs.state.initialized) mutableState.value = json.decodeFromString(UsageArchive.serializer(),
            json.encodeToString(UsageArchive.serializer(), inputs.state.archive))
    }

    private fun report(operation: String, failure: Throwable) {
        mutableFailure.value = if (failure is UsageInputRejected) failure.message ?: unavailableMessage else unavailableMessage
        AppLog.error("usage", "${operation}_failed", IllegalStateException("Usage accounting failed"),
            mapOf("causeType" to (failure::class.simpleName ?: "Failure"), "result" to "known_archive_retained"))
    }

    override suspend fun <T> measure(profile: LlmProfile, block: suspend () -> T): T {
        val observation = captureObservation()
        val attribution = currentCoroutineContext()[UsageOwner]
        val owner = attribution?.scope ?: UsageScope()
        val call = UsageCall()
        val initial = UsageRecord(Id.new(), scope = owner, provider = profile.provider.name, model = profile.modelId,
            subscription = profile.provider == ProviderType.OPENAI_SUBSCRIPTION)
        record(observation, initial)
        var completed = false
        var primary: Throwable? = null
        try { return withContext(call) { block() }.also { completed = true } }
        catch (failure: Exception) { primary = failure; throw failure }
        finally { withContext(NonCancellable) {
            try {
                val result = call.result.value
                val pricing = profile.modelCatalog.firstOrNull { it.id == profile.modelId }?.pricing
                record(observation, initial.copy(tokens = result.tokens,
                    cost = if (initial.subscription) null else result.cost ?: pricing?.estimate(result.tokens),
                    completed = completed, requests = result.requests))
                owner.conversationId?.takeIf { it.startsWith("coding:") && attribution?.updatesContext != false }?.let { id ->
                    context(observation, ContextUsageSnapshot(id, profile.modelId, result.contextTokens ?: result.tokens.totalTokens,
                        result.contextLimit ?: profile.advanced.safeContextLimit.toLong()))
                }
            } catch (cleanup: Throwable) {
                val original = primary
                if (original == null) throw cleanup
                if (original !== cleanup) original.addSuppressed(cleanup)
            }
        } }
    }

    private fun stamp() = UsageMachine.Stamp(Id.new(), Id.now())
    private companion object { const val unavailableMessage = "Не удалось подтвердить статистику расходов. Сохранённые данные оставлены без изменений." }
}

fun UsageLedger(repository: UsageRepository, journal: EventJournal, payloads: KeyValueStore, json: Json): UsageLedger =
    DefaultUsageLedger(repository, journal, payloads, json)
