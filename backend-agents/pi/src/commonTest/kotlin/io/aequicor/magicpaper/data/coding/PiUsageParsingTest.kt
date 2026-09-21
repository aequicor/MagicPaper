package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.json.*
import kotlin.test.*

class PiUsageParsingTest {
    @Test fun piPlaceholderCountersDoNotClaimKnownZeroUsage() {
        assertEquals(TokenUsage(), PiUsageParsing.tokens(Json.parseToJsonElement("""{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"totalTokens":0}""").jsonObject))
    }
}
