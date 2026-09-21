package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.json.*

internal fun JsonObject.count(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
private fun JsonObject.price(key: String): Double? = (get(key) as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }

internal object PiUsageParsing {
    fun tokens(usage: JsonObject): TokenUsage {
        val tokens = TokenUsage(usage.count("input"), usage.count("output"), usage.count("cacheRead"),
            usage.count("cacheWrite"), usage.count("reasoning"), total = usage.count("totalTokens"))
        // pi-ai initializes every counter to zero before any provider usage arrives.
        // A real model response cannot establish that this sentinel was a free, zero-token call.
        return if (tokens.totalTokens == 0L && listOf(tokens.input, tokens.output, tokens.cacheRead, tokens.cacheWrite)
                .all { it == null || it == 0L }) TokenUsage() else tokens
    }
    fun cost(usage: JsonObject): UsageCost? = usage.obj("cost")?.price("total")?.takeIf { it > 0 }?.let { UsageCost(it, kind = CostKind.ESTIMATED) }
}
