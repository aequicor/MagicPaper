package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.*

internal fun JsonObject.count(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
internal fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
internal fun JsonObject.price(key: String): Double? = (get(key) as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }

object UsageParsing {
    fun openAi(usage: JsonObject): TokenUsage {
        val read = usage.obj("prompt_tokens_details")?.count("cached_tokens") ?: usage.obj("input_tokens_details")?.count("cached_tokens")
        val write = usage.obj("prompt_tokens_details")?.count("cache_write_tokens") ?: usage.count("cache_creation_input_tokens") ?: usage.obj("input_tokens_details")?.count("cache_creation_tokens")
        return TokenUsage(
            (usage.count("prompt_tokens") ?: usage.count("input_tokens"))?.let { (it - (read ?: 0) - (write ?: 0)).coerceAtLeast(0) },
            usage.count("completion_tokens") ?: usage.count("output_tokens"), read, write,
            usage.obj("completion_tokens_details")?.count("reasoning_tokens") ?: usage.obj("output_tokens_details")?.count("reasoning_tokens"),
            usage.obj("completion_tokens_details")?.count("cached_tokens") ?: usage.obj("output_tokens_details")?.count("cached_tokens"),
            usage.count("total_tokens"))
    }
    fun anthropic(usage: JsonObject) = TokenUsage(usage.count("input_tokens"), usage.count("output_tokens"),
        usage.count("cache_read_input_tokens"), usage.count("cache_creation_input_tokens"))
    fun google(usage: JsonObject): TokenUsage {
        val cached = usage.count("cachedContentTokenCount")
        val thoughts = usage.count("thoughtsTokenCount")
        return TokenUsage(usage.count("promptTokenCount")?.let { (it - (cached ?: 0)).coerceAtLeast(0) },
            usage.count("candidatesTokenCount")?.let { it + (thoughts ?: 0) }, cached, reasoning = thoughts, total = usage.count("totalTokenCount"))
    }
    fun pi(usage: JsonObject): TokenUsage {
        val tokens = TokenUsage(usage.count("input"), usage.count("output"), usage.count("cacheRead"),
            usage.count("cacheWrite"), usage.count("reasoning"), total = usage.count("totalTokens"))
        // pi-ai initializes every counter to zero before any provider usage arrives.
        // A real model response cannot establish that this sentinel was a free, zero-token call.
        return if (tokens.totalTokens == 0L && listOf(tokens.input, tokens.output, tokens.cacheRead, tokens.cacheWrite)
                .all { it == null || it == 0L }) TokenUsage() else tokens
    }
    fun piCost(usage: JsonObject): UsageCost? = usage.obj("cost")?.price("total")?.takeIf { it > 0 }?.let { UsageCost(it, kind = CostKind.ESTIMATED) }
    fun codex(usage: JsonObject) = TokenUsage(usage.count("inputTokens")?.let { (it - (usage.count("cachedInputTokens") ?: 0)).coerceAtLeast(0) },
        usage.count("outputTokens"), usage.count("cachedInputTokens"), reasoning = usage.count("reasoningOutputTokens"), total = usage.count("totalTokens"))

    suspend fun report(body: String, provider: ProviderType, json: Json) {
        val call = currentCoroutineContext()[UsageCall] ?: return
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return
        val usage = root.obj(if (provider == ProviderType.GOOGLE) "usageMetadata" else "usage") ?: return
        val tokens = when (provider) { ProviderType.ANTHROPIC -> anthropic(usage); ProviderType.GOOGLE -> google(usage); else -> openAi(usage) }
        call.result.value = UsageCallResult(tokens, usage.price("cost")?.let { UsageCost(it) })
    }
}
