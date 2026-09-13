package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.*

class UsageLedgerTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun ledger(store: KeyValueStore = InMemoryKeyValueStore()) = UsageLedger(JsonUsageRepository(store, json))
    private fun tokens(input: Long, output: Long) = TokenUsage(input, output, 0, 0, total = input + output)

    @Test fun cumulativeResumeDoesNotChargeOldHistoryOrRepeatedNotifications() = runTest {
        val store = InMemoryKeyValueStore()
        val first = ledger(store)
        val record = UsageRecord("sample", scope = UsageScope(conversationId = "coding:worker", parentConversationId = "coding:plan"))
        first.cumulative("thread", "turn1", tokens(1100, 110), tokens(100, 10), record)
        first.cumulative("thread", "turn1", tokens(1100, 110), tokens(100, 10), record)
        val restored = ledger(store)
        restored.cumulative("thread", "turn1", tokens(1100, 110), tokens(100, 10), record)
        restored.cumulative("thread", "turn2", tokens(1300, 130), tokens(200, 20), record)
        assertEquals(listOf(110L, 220L), restored.state.value.records.map { it.tokens.totalTokens })
        assertEquals(2, restored.state.value.records.count { it.scope.includes("coding:plan") })
        assertEquals(2, restored.state.value.records.count { it.scope.includes("coding:worker") })
        assertEquals(2L, restored.state.value.records.sumOf { it.requests })
    }

    @Test fun independentParallelOwnersAndPendingRequestsSurviveStorageRoundtrip() = runTest {
        val store = InMemoryKeyValueStore(); val tracker = ledger(store)
        coroutineScope { repeat(30) { i -> launch {
            val pending = UsageRecord("pending:$i", scope = UsageScope.chat("$i"))
            tracker.record(pending)
            tracker.record(pending.copy(id = "final:$i", tokens = tokens(i.toLong(), 1)), pending.id)
            tracker.record(pending.copy(id = "final:$i", tokens = tokens(i.toLong(), 1)))
        } } }
        val restored = ledger(store).state.value
        assertEquals(30, restored.records.size)
        assertEquals(465L, restored.records.sumOf { it.tokens.totalTokens ?: 0 })
        assertTrue(restored.records.all { it.scope.conversationId == "chat:${it.id.substringAfter(':')}" })
    }

    @Test fun failedModelCallKeepsKnownUsageAndSubscriptionDoesNotUseApiRates() = runTest {
        val tracker = ledger()
        val profile = LlmProfile(id = "p", name = "Test", provider = ProviderType.OPENAI_SUBSCRIPTION, modelId = "m",
            modelCatalog = listOf(ProviderModel("m", pricing = ModelPricing(.01, .02))))
        assertFailsWith<IllegalStateException> {
            withContext(UsageOwner(UsageScope.chat("test"))) {
                tracker.measure(profile) {
                    currentCoroutineContext()[UsageCall]!!.result.value = UsageCallResult(tokens(10, 5), UsageCost(99.0))
                    error("connection lost")
                }
            }
        }
        val entry = tracker.state.value.records.single()
        assertEquals(15L, entry.tokens.totalTokens)
        assertEquals("chat:test", entry.scope.conversationId)
        assertTrue(entry.subscription); assertNull(entry.cost); assertFalse(entry.completed)
    }

    @Test fun cancelledCallsRetainUsageWithoutReplacingTheForegroundContext() = runTest {
        val store = InMemoryKeyValueStore(); val tracker = ledger(store)
        val before = ContextUsageSnapshot("coding:plan", "foreground", 900, 1000)
        tracker.context(before)
        assertFailsWith<CancellationException> {
            withContext(UsageOwner(UsageScope("coding:plan"), updatesContext = false)) {
                tracker.measure(LlmProfile("p", "Background", modelId = "background")) {
                    currentCoroutineContext()[UsageCall]!!.result.value = UsageCallResult(tokens(10, 5))
                    throw CancellationException("user cancelled")
                }
            }
        }
        val restored = ledger(store).state.value
        assertEquals(15L, restored.records.single().tokens.totalTokens)
        assertEquals(before, restored.contexts["coding:plan"])
        val recorder = CodingRunRecorder()
        recorder.apply(CodingEvent.Compaction(CompactionStatus("c", CompactionPhase.STARTED)))
        assertFalse(recorder.message("m", 1).steps.single().running)
    }

    @Test fun costUsesDisjointBucketsAndRequiresRatesForNonzeroUsage() {
        val rates = ModelPricing(input = .01, output = .02, cacheRead = .001)
        val usage = TokenUsage(10, 5, 100, 0, reasoning = 3)
        assertEquals(.3, rates.estimate(usage)!!.amount, .000001)
        assertEquals(CostKind.ESTIMATED, rates.estimate(usage)!!.kind)
        assertNull(rates.estimate(usage.copy(cacheWrite = 2)))
        assertNull(rates.estimate(TokenUsage()))
        assertEquals(0.0, ModelPricing(0.0, 0.0).estimate(TokenUsage(10, 5))!!.amount)
    }

    @Test fun archiveAndOldProfileFormatsRemainCompatible() = runTest {
        val tracker = ledger()
        tracker.record(UsageRecord("r", tokens = tokens(50, 5)))
        tracker.context(ContextUsageSnapshot("coding:s", "m", 55, 1000))
        val bundle = ProfileBundle(exportedAt = 1, settings = AppSettings(), plugins = emptyList(), sessions = emptyList(), usage = tracker.state.value)
        val decoded = json.decodeFromString<ProfileBundle>(json.encodeToString(bundle))
        assertEquals(tracker.state.value, decoded.usage)
        assertTrue(json.decodeFromString<ProfileBundle>("""{"exportedAt":1,"settings":{},"plugins":[],"sessions":[]}""").usage.records.isEmpty())
        tracker.clear(); assertTrue(tracker.state.value.records.isEmpty()); assertTrue(tracker.state.value.contexts.isEmpty())
        tracker.replace(decoded.usage); assertEquals(55L, tracker.state.value.records.single().tokens.totalTokens)
    }

    @Test fun compactionUpdatesOneVisibleSystemStepAndPreservesChronology() {
        val recorder = CodingRunRecorder()
        recorder.apply(CodingEvent.TextDelta("Before"))
        recorder.apply(CodingEvent.Compaction(CompactionStatus("c", CompactionPhase.STARTED)))
        recorder.apply(CodingEvent.Compaction(CompactionStatus("c", CompactionPhase.COMPLETED)))
        recorder.apply(CodingEvent.TextDelta("After"))
        val message = recorder.message("m", 1)
        assertEquals(listOf(CodingStepKind.ANSWER, CodingStepKind.SYSTEM, CodingStepKind.ANSWER), message.steps.map { it.kind })
        assertFalse(message.steps[1].running)
        assertEquals("Контекст автоматически сжат", message.steps[1].title)
        assertEquals(.5f, ContextUsageSnapshot("s", "m", 500, 1000).fraction)
        assertNull(ContextUsageSnapshot("s", "m", null, 1000).fraction)
    }
}
