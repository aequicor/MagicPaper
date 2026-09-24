package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.data.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class MeteredRuntimeUsageTest {
    @Test fun repeatedRealCompactionsKeepSeparateExpensesAndClearContext() = runTest {
        val usageStore = InMemoryKeyValueStore()
        val ledger = UsageLedger(JsonUsageRepository(usageStore, Json), InMemoryEventJournal(), usageStore, Json)
        val delegate = object : CodingRuntime by NoopCodingRuntime {
            override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
                repeat(2) {
                    emit(CodingEvent.ContextUpdated(900, 1000))
                    emit(CodingEvent.Compaction(CompactionStatus("", CompactionPhase.STARTED)))
                    repeat(2) { emit(CodingEvent.UsageObserved(TokenUsage(900, 50), "compaction:same-summary")) }
                    emit(CodingEvent.Compaction(CompactionStatus("", CompactionPhase.COMPLETED)))
                }
            }
        }
        val recorder = CodingRunRecorder()
        MeteredCodingRuntime(delegate, ledger).run(CodingProject("p", "P", "/fixture", 1),
            CodingSession("s", "p", "S", 1), "test", LlmProfile("p", "M"), emptyList()).collect { recorder.apply(it) }
        assertEquals(2, ledger.state.value.records.size)
        assertEquals(1900L, ledger.state.value.records.sumOf { it.tokens.totalTokens ?: 0 })
        assertNull(ledger.state.value.contexts["coding:s"]?.used)
        assertEquals(2, recorder.message("m", 1).steps.count { it.systemEvent?.phase == CompactionPhase.COMPLETED })
    }

    @Test fun theWindowAnEngineReportedOutlastsANewRunAndACompaction() = runTest {
        val usageStore = InMemoryKeyValueStore()
        val ledger = UsageLedger(JsonUsageRepository(usageStore, Json), InMemoryEventJournal(), usageStore, Json)
        var compact = false
        val delegate = object : CodingRuntime by NoopCodingRuntime {
            override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
                if (compact) emit(CodingEvent.Compaction(CompactionStatus("", CompactionPhase.STARTED)))
                else emit(CodingEvent.ContextUpdated(5_000, 1_000_000))
            }
        }
        val runtime = MeteredCodingRuntime(delegate, ledger)
        val project = CodingProject("p", "P", "/fixture", 1)
        val session = CodingSession("s", "p", "S", 1, engine = CodingEngine.CLAUDE_CODE)
        // The profile's own limit bounds the application's requests; the engine keeps a larger conversation.
        val profile = LlmProfile("p", "Claude", modelId = "opus", advanced = AdvancedLlmOptions(contextLimit = 128_000))
        runtime.run(project, session, "first", profile, emptyList()).collect()
        compact = true
        runtime.run(project, session, "second", profile, emptyList()).collect()
        val context = checkNotNull(ledger.state.value.contexts["coding:s"])
        assertNull(context.used, "A compaction leaves the size unknown until the engine reports it")
        assertEquals(1_000_000L, context.limit)
        runtime.run(project, session, "other", profile.copy(modelId = "haiku"), emptyList()).collect()
        assertEquals(128_000L, ledger.state.value.contexts["coding:s"]?.limit, "Another model's window is not carried over")
    }

    @Test fun isolatesParallelStageContextAndDeduplicatesBridgeMetrics() = runTest {
        val usageStore = InMemoryKeyValueStore()
        val ledger = UsageLedger(JsonUsageRepository(usageStore, Json), InMemoryEventJournal(), usageStore, Json)
        val delegate = object : CodingRuntime by NoopCodingRuntime {
            override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
                val environment = currentCoroutineContext()[RuntimeUsageContext]!!
                assertEquals("coding:${session.id}", environment.owner.conversationId)
                emit(CodingEvent.UsageObserved(TokenUsage(10, 5), "response", accounting = false))
                environment.ledger.record(environment.observation, UsageRecord("bridge:${session.id}", scope = environment.owner, tokens = TokenUsage(10, 5)))
                emit(CodingEvent.ContextUpdated(50, 1000))
                emit(CodingEvent.Finished)
            }
        }
        val runtime = MeteredCodingRuntime(delegate, ledger)
        val project = CodingProject("p", "P", "/fixture", 1)
        val profile = LlmProfile("profile", "Fixture", modelId = "fixture")
        coroutineScope { listOf("a", "b").forEach { id -> launch {
            runtime.run(project, CodingSession(id, "p", id, 1, engine = CodingEngine.CODEX, parentSessionId = "plan"), "test", profile, emptyList()).collect()
        } } }
        assertEquals(2, ledger.state.value.records.size)
        assertTrue(ledger.state.value.records.all { it.scope.includes("coding:plan") })
        assertEquals(setOf("coding:a", "coding:b"), ledger.state.value.contexts.keys)
        assertNull(ledger.state.value.contexts["coding:plan"])
    }
}
