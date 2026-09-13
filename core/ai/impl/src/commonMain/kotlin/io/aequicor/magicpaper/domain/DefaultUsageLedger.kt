package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.util.Id

class DefaultUsageLedger(private val repository: UsageRepository) : UsageLedger {
    private val lock = Mutex()
    private val _failure = MutableStateFlow<String?>(null)
    override val failure = _failure.asStateFlow()
    private var unreadableArchive = false
    private val _state = MutableStateFlow(runCatching { repository.load() }.getOrElse {
        unreadableArchive = true
        _failure.value = "Не удалось прочитать сохранённую статистику; доступны расходы текущего запуска"; UsageArchive()
    })
    override val state = _state.asStateFlow()

    private suspend fun update(change: (UsageArchive) -> UsageArchive) = withContext(NonCancellable) { lock.withLock {
        val next = change(_state.value)
        if (next == _state.value) return@withLock
        _state.value = next
        if (unreadableArchive) return@withLock // Keep the original archive intact until explicit import/reset.
        try { withContext(Dispatchers.Default) { repository.save(next) }; _failure.value = null }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { _failure.value = "Не удалось сохранить статистику расходов" }
    } }

    override suspend fun record(record: UsageRecord, replacesId: String?) = update { archive ->
        val old = if (replacesId != null && replacesId != record.id) archive.copy(records = archive.records.filterNot { it.id == replacesId }) else archive
        val index = old.records.indexOfFirst { it.id == record.id }
        old.copy(records = if (index < 0) old.records + record else old.records.toMutableList().apply { set(index, record) })
    }
    override suspend fun context(snapshot: ContextUsageSnapshot) = update { old ->
        val previous = old.contexts[snapshot.conversationId]
        if (previous != null && previous.updatedAt > snapshot.updatedAt) old
        else old.copy(contexts = old.contexts + (snapshot.conversationId to snapshot))
    }
    override suspend fun cumulative(key: String, fingerprint: String, total: TokenUsage, last: TokenUsage, record: UsageRecord) = update { old ->
        val cursor = old.cursors[key]
        if (cursor?.fingerprint == fingerprint || cursor?.tokens == total) return@update old
        // The first observation in a pre-existing native thread must not charge old history.
        val tokens = if (cursor == null || (total.totalTokens ?: 0) < (cursor.tokens.totalTokens ?: 0)) last else total.delta(cursor.tokens)
        old.copy(records = old.records + record.copy(id = "$key:$fingerprint", tokens = tokens),
            cursors = old.cursors + (key to UsageCursor(total, fingerprint)))
    }
    override suspend fun replace(archive: UsageArchive) = update { unreadableArchive = false; archive.copy(records = archive.records.distinctBy { it.id }) }
    override suspend fun clear() = replace(UsageArchive())

    override suspend fun <T> measure(profile: LlmProfile, block: suspend () -> T): T {
        val attribution = currentCoroutineContext()[UsageOwner]
        val owner = attribution?.scope ?: UsageScope()
        val call = UsageCall()
        val initial = UsageRecord(Id.new(), scope = owner, provider = profile.provider.name, model = profile.modelId,
            subscription = profile.provider == ProviderType.OPENAI_SUBSCRIPTION)
        record(initial)
        var completed = false
        try { return withContext(call) { block() }.also { completed = true } }
        finally { withContext(NonCancellable) {
            val result = call.result.value
            val pricing = profile.modelCatalog.firstOrNull { it.id == profile.modelId }?.pricing
            record(initial.copy(tokens = result.tokens, cost = if (initial.subscription) null else result.cost ?: pricing?.estimate(result.tokens),
                completed = completed, requests = result.requests))
            owner.conversationId?.takeIf { it.startsWith("coding:") && attribution?.updatesContext != false }?.let { id ->
                context(ContextUsageSnapshot(id, profile.modelId, result.contextTokens ?: result.tokens.totalTokens,
                    result.contextLimit ?: profile.advanced.safeContextLimit.toLong()))
            }
        } }
    }
}

fun UsageLedger(repository: UsageRepository): UsageLedger = DefaultUsageLedger(repository)
