package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The feature reads model sources from the wrapped runtime the graph hands out, not from the raw
 * engine runtime. A wrapper that forgot to forward them would leave the catalog silently empty.
 */
class RuntimeWrapperModelSourcesTest {
    @Test fun meteringWrapperForwardsTheEnginesModelSources() = runTest {
        val source = CodingModelSource { listOf(CodingModel("openai", "gpt-fixture")) }
        val raw = object : CodingRuntime by NoopCodingRuntime { override val modelSources = mapOf(CodingEngine.CODEX to source) }
        val store = InMemoryKeyValueStore()
        val wrapped = MeteredCodingRuntime(raw, UsageLedger(JsonUsageRepository(store, Json), InMemoryEventJournal(), store, Json))
        assertEquals(setOf(CodingEngine.CODEX), wrapped.modelSources.keys)
        assertEquals(listOf("gpt-fixture"), wrapped.modelSources.getValue(CodingEngine.CODEX).fetch().map { it.id })
    }
}
