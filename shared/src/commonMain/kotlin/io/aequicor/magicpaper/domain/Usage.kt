package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Input buckets are disjoint; reasoning and cached output are subsets of output. */
@Serializable
data class TokenUsage(
    val input: Long? = null, val output: Long? = null,
    val cacheRead: Long? = null, val cacheWrite: Long? = null,
    val reasoning: Long? = null, val cachedOutput: Long? = null, val total: Long? = null,
) {
    val totalTokens: Long? get() = total ?: if (input != null && output != null)
        input + output + (cacheRead ?: 0) + (cacheWrite ?: 0) else null
    fun delta(previous: TokenUsage): TokenUsage {
        fun difference(now: Long?, before: Long?) = now?.let { (it - (before ?: 0)).coerceAtLeast(0) }
        return TokenUsage(difference(input, previous.input), difference(output, previous.output),
            difference(cacheRead, previous.cacheRead), difference(cacheWrite, previous.cacheWrite),
            difference(reasoning, previous.reasoning), difference(cachedOutput, previous.cachedOutput),
            difference(total, previous.total))
    }
}

@Serializable enum class UsageKind { MODEL, SEARCH, CONTENT }
@Serializable enum class CostKind { REPORTED, ESTIMATED }
@Serializable data class UsageCost(val amount: Double, val currency: String = "USD", val kind: CostKind = CostKind.REPORTED)
@Serializable data class ModelPricing(
    val input: Double? = null, val output: Double? = null,
    val cacheRead: Double? = null, val cacheWrite: Double? = null,
    val currency: String = "USD",
) {
    /** Catalog rates are per token; an absent rate is not a free request. */
    fun estimate(tokens: TokenUsage): UsageCost? {
        val buckets = listOf(tokens.input to input, tokens.output to output, tokens.cacheRead to cacheRead, tokens.cacheWrite to cacheWrite)
        if (tokens.input == null || tokens.output == null || buckets.any { (count, rate) -> (count ?: 0) > 0 && rate == null }) return null
        return UsageCost(buckets.sumOf { (count, rate) -> (count ?: 0) * (rate ?: 0.0) }, currency, CostKind.ESTIMATED)
    }
}

@Serializable data class UsageScope(
    val conversationId: String? = null, val parentConversationId: String? = null,
    val projectId: String? = null, val planId: String? = null,
) {
    fun includes(id: String) = conversationId == id || parentConversationId == id
    companion object {
        fun chat(id: String) = UsageScope("chat:$id")
        fun coding(session: CodingSession) = UsageScope("coding:${session.id}",
            session.parentSessionId?.let { "coding:$it" }, session.projectId, session.planId)
    }
}

class UsageOwner(val scope: UsageScope, val updatesContext: Boolean = true) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<UsageOwner>
}

@Serializable data class UsageRecord(
    val id: String, val createdAt: Long = Id.now(), val scope: UsageScope = UsageScope(),
    val kind: UsageKind = UsageKind.MODEL, val provider: String = "", val model: String = "",
    val requests: Long = 1, val pages: Long = 0, val tokens: TokenUsage = TokenUsage(),
    val cost: UsageCost? = null, val subscription: Boolean = false, val completed: Boolean = false,
    val contentRequests: Long = if (kind == UsageKind.CONTENT) requests else 0,
)

@Serializable data class ContextUsageSnapshot(
    val conversationId: String, val model: String, val used: Long? = null, val limit: Long? = null,
    val approximate: Boolean = false, val updatedAt: Long = Id.now(),
) {
    val fraction: Float? get() = if (used != null && limit != null && limit > 0) (used.toDouble() / limit).toFloat() else null
}

@Serializable data class UsageCursor(val tokens: TokenUsage, val fingerprint: String)
@Serializable data class UsageArchive(
    val startedAt: Long = Id.now(), val records: List<UsageRecord> = emptyList(),
    val contexts: Map<String, ContextUsageSnapshot> = emptyMap(), val cursors: Map<String, UsageCursor> = emptyMap(),
)

interface UsageRepository {
    fun load(): UsageArchive
    fun save(archive: UsageArchive)
}

/** One ledger across API transports, background work and both desktop engines. */
class UsageLedger(private val repository: UsageRepository) {
    private val lock = Mutex()
    private val _failure = MutableStateFlow<String?>(null)
    val failure = _failure.asStateFlow()
    private var unreadableArchive = false
    private val _state = MutableStateFlow(runCatching { repository.load() }.getOrElse {
        unreadableArchive = true
        _failure.value = "Не удалось прочитать сохранённую статистику; доступны расходы текущего запуска"; UsageArchive()
    })
    val state = _state.asStateFlow()

    private suspend fun update(change: (UsageArchive) -> UsageArchive) = withContext(NonCancellable) { lock.withLock {
        val next = change(_state.value)
        if (next == _state.value) return@withLock
        _state.value = next
        if (unreadableArchive) return@withLock // Keep the original archive intact until explicit import/reset.
        try { withContext(Dispatchers.Default) { repository.save(next) }; _failure.value = null }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { _failure.value = "Не удалось сохранить статистику расходов" }
    } }

    suspend fun record(record: UsageRecord, replacesId: String? = null) = update { archive ->
        val old = if (replacesId != null && replacesId != record.id) archive.copy(records = archive.records.filterNot { it.id == replacesId }) else archive
        val index = old.records.indexOfFirst { it.id == record.id }
        old.copy(records = if (index < 0) old.records + record else old.records.toMutableList().apply { set(index, record) })
    }
    suspend fun context(snapshot: ContextUsageSnapshot) = update { old ->
        val previous = old.contexts[snapshot.conversationId]
        if (previous != null && previous.updatedAt > snapshot.updatedAt) old
        else old.copy(contexts = old.contexts + (snapshot.conversationId to snapshot))
    }
    suspend fun cumulative(key: String, fingerprint: String, total: TokenUsage, last: TokenUsage, record: UsageRecord) = update { old ->
        val cursor = old.cursors[key]
        if (cursor?.fingerprint == fingerprint || cursor?.tokens == total) return@update old
        // The first observation in a pre-existing native thread must not charge old history.
        val tokens = if (cursor == null || (total.totalTokens ?: 0) < (cursor.tokens.totalTokens ?: 0)) last else total.delta(cursor.tokens)
        old.copy(records = old.records + record.copy(id = "$key:$fingerprint", tokens = tokens),
            cursors = old.cursors + (key to UsageCursor(total, fingerprint)))
    }
    suspend fun replace(archive: UsageArchive) = update { unreadableArchive = false; archive.copy(records = archive.records.distinctBy { it.id }) }
    suspend fun clear() = replace(UsageArchive())

    suspend fun <T> measure(profile: LlmProfile, block: suspend () -> T): T {
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

data class UsageCallResult(val tokens: TokenUsage = TokenUsage(), val cost: UsageCost? = null,
    val contextTokens: Long? = null, val contextLimit: Long? = null, val requests: Long = 1)
class UsageCall : AbstractCoroutineContextElement(Key) {
    val result = MutableStateFlow(UsageCallResult())
    companion object Key : CoroutineContext.Key<UsageCall>
}

@Serializable data class CompactionStatus(val id: String, val phase: CompactionPhase, val reason: String = "", val detail: String = "") {
    val text: String get() = when (phase) {
        CompactionPhase.STARTED -> if (reason == "manual") "Сжатие контекста…" else "Автоматическое сжатие контекста…"
        CompactionPhase.COMPLETED -> if (reason == "manual") "Контекст сжат" else "Контекст автоматически сжат"
        CompactionPhase.CANCELLED -> "Сжатие контекста отменено"
        CompactionPhase.FAILED -> "Не удалось сжать контекст"
    } + detail.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
}
@Serializable enum class CompactionPhase { STARTED, COMPLETED, CANCELLED, FAILED }
