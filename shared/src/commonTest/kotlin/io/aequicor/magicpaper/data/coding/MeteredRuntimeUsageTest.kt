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
        val ledger = UsageLedger(JsonUsageRepository(InMemoryKeyValueStore(), Json))
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

    @Test fun isolatesParallelStageContextAndDeduplicatesBridgeMetrics() = runTest {
        val ledger = UsageLedger(JsonUsageRepository(InMemoryKeyValueStore(), Json))
        val delegate = object : CodingRuntime by NoopCodingRuntime {
            override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
                val environment = currentCoroutineContext()[RuntimeUsageContext]!!
                assertEquals("coding:${session.id}", environment.owner.conversationId)
                emit(CodingEvent.UsageObserved(TokenUsage(10, 5), "response", accounting = false))
                environment.ledger.record(UsageRecord("bridge:${session.id}", scope = environment.owner, tokens = TokenUsage(10, 5)))
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
