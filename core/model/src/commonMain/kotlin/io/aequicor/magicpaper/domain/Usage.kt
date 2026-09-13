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



/** One ledger across API transports, background work and both desktop engines. */


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
