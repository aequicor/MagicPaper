package io.aequicor.magicpaper.domain

import kotlinx.serialization.json.Json
import kotlin.test.*

class UsageMachineTest {
    private fun stamp(id: String) = UsageMachine.Stamp(id, 10)
    private fun initialized() = accept(UsageMachine.initial(), UsageMachine.Fact.Initialized(UsageArchive(startedAt = 1), stamp("generation")))
    private val capture = UsageObservation.Captured("generation")
    private fun record(id: String = "request", completed: Boolean = false) = UsageRecord(id, createdAt = 2,
        scope = UsageScope.chat("owner"), provider = "provider", model = "model", completed = completed)
    private fun fact(value: UsageRecord, replaces: String? = null) = UsageMachine.Fact.Recorded(capture, value, replaces, stamp("record"))
    private fun accept(state: UsageMachine.State, input: UsageMachine.Input): UsageMachine.State {
        val result = UsageMachine.reduce(state, input)
        assertNull(result.rejection)
        return result.state
    }

    @Test fun stateInputMatrixRejectsUninitializedStaleAndUnknownObservations() {
        val ready = initialized()
        val cleared = accept(ready, UsageMachine.Intent.Clear(stamp("new-generation")))
        val unknown = accept(ready, UsageMachine.Fact.PersistenceUnknown(stamp("unknown")))
        val observations = listOf<UsageMachine.Input>(fact(record()),
            UsageMachine.Fact.ContextObserved(capture, ContextUsageSnapshot("chat:owner", "m", updatedAt = 3), stamp("context")),
            cumulative("one", 10, 10))
        for (state in listOf(UsageMachine.initial(), cleared, unknown)) for (input in observations) {
            val result = UsageMachine.reduce(state, input)
            assertEquals(state, result.state)
            assertNotNull(result.rejection)
        }
        for (input in observations) assertNull(UsageMachine.reduce(ready, input).rejection)
        for (input in listOf(UsageMachine.Intent.Clear(stamp("clear")), UsageMachine.Intent.Import(UsageArchive(startedAt = 1), stamp("import")))) {
            assertNotNull(UsageMachine.reduce(unknown, input).rejection)
        }
    }

    @Test fun replacementRetiresPendingIdentityAndRequiresSameOwner() {
        val pending = accept(initialized(), fact(record()))
        val final = record("answer", true).copy(tokens = TokenUsage(10, 5))
        assertNotNull(UsageMachine.reduce(pending, fact(final.copy(scope = UsageScope.chat("other")), "request")).rejection)
        val replaced = accept(pending, fact(final, "request"))
        assertEquals(listOf("answer"), replaced.archive.records.map { it.id })
        assertEquals(setOf("request"), replaced.retiredIds)
        assertEquals(replaced, accept(replaced, fact(final.copy(createdAt = 20), "request")))
        assertNotNull(UsageMachine.reduce(replaced, fact(record())).rejection)
        assertNotNull(UsageMachine.reduce(replaced, fact(final.copy(tokens = TokenUsage(20, 5)))).rejection)
    }

    @Test fun latePendingUpdateCannotEraseKnownFailedCallUsage() {
        val known = accept(initialized(), fact(record().copy(tokens = TokenUsage(10, 5), cost = UsageCost(.1))))
        assertNotNull(UsageMachine.reduce(known, fact(record())).rejection)
        assertEquals(15L, known.archive.records.single().tokens.totalTokens)
    }

    @Test fun historicalCumulativeFingerprintCannotRewindOrDoubleCharge() {
        val first = accept(initialized(), cumulative("one", 100, 10))
        val second = accept(first, cumulative("two", 120, 20))
        assertEquals(second, accept(second, cumulative("one", 100, 10)))
        val third = accept(second, cumulative("three", 130, 10))
        assertEquals(listOf(10L, 20L, 10L), third.archive.records.map { it.tokens.totalTokens })
        assertEquals("three", third.archive.cursors.getValue("thread").fingerprint)
        assertNotNull(UsageMachine.reduce(third, cumulative("one", 101, 10)).rejection)
        assertNotNull(UsageMachine.reduce(third, cumulative("one", 100, 11)).rejection)
    }

    @Test fun legacyCumulativeBindsOnlyExactLatestProofAndNeverChargesAmbiguousHistory() {
        val old = record("thread:one", true).copy(tokens = TokenUsage(total = 10))
        val archive = UsageArchive(startedAt = 1, records = listOf(old), cursors = mapOf("thread" to UsageCursor(TokenUsage(total = 100), "one")))
        val imported = accept(UsageMachine.initial(), UsageMachine.Fact.Initialized(archive, stamp("generation")))
        val bound = accept(imported, cumulative("one", 100, 10))
        assertEquals(archive, bound.archive)
        val ambiguous = imported.copy(archive = archive.copy(cursors = mapOf("thread" to UsageCursor(TokenUsage(total = 120), "two"))))
        assertNotNull(UsageMachine.reduce(ambiguous, cumulative("one", 100, 10)).rejection)
    }

    @Test fun contextRejectsWrongGenerationAndIgnoresOlderObservation() {
        val newer = ContextUsageSnapshot("chat:owner", "m", 10, 100, updatedAt = 20)
        val saved = accept(initialized(), UsageMachine.Fact.ContextObserved(capture, newer, stamp("context")))
        assertEquals(saved, accept(saved, UsageMachine.Fact.ContextObserved(capture, newer.copy(used = 5, updatedAt = 10), stamp("old"))))
        assertNotNull(UsageMachine.reduce(saved, UsageMachine.Fact.ContextObserved(UsageObservation.Captured("foreign"), newer, stamp("foreign"))).rejection)
    }

    @Test fun malformedArchivesFailWithoutDiscardingKnownRecords() {
        val known = accept(initialized(), fact(record()))
        val malformed = listOf(UsageArchive(startedAt = 1, records = listOf(record(), record())),
            UsageArchive(startedAt = 1, records = listOf(record().copy(tokens = TokenUsage(-1, 2)))),
            UsageArchive(startedAt = 1, contexts = mapOf("wrong" to ContextUsageSnapshot("owner", "m", updatedAt = 1))))
        for (archive in malformed) {
            val result = UsageMachine.reduce(known, UsageMachine.Intent.Import(archive, stamp("import")))
            assertNotNull(result.rejection); assertEquals(known, result.state)
        }
    }

    @Test fun serializedTraceReplaysExactlyWithoutClockOrEffects() {
        val json = Json { encodeDefaults = true }
        val trace = listOf<UsageMachine.Input>(UsageMachine.Fact.Initialized(UsageArchive(startedAt = 1), stamp("generation")),
            fact(record()), fact(record().copy(tokens = TokenUsage(10, 5), completed = true)),
            cumulative("one", 100, 10), cumulative("two", 110, 10), cumulative("one", 100, 10))
        val original = trace.fold(UsageMachine.initial(), ::accept)
        val decoded = trace.map { json.decodeFromString(UsageMachine.Input.serializer(), json.encodeToString(UsageMachine.Input.serializer(), it)) }
        assertEquals(original, decoded.fold(UsageMachine.initial(), ::accept))
    }

    private fun cumulative(fingerprint: String, total: Long, last: Long) = UsageMachine.Fact.CumulativeObserved(capture,
        UsageMachine.CumulativeProof("thread", fingerprint, TokenUsage(total = total), TokenUsage(total = last), record("source", true)), stamp("counter"))
}
