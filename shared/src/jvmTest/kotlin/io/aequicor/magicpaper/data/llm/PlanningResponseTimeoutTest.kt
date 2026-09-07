package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.CodingStep
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlanningResponseTimeoutTest {
    @Test fun activeResponseCanExceedTimeoutAndReportsProgress() = runTest {
        val steps = mutableListOf<CodingStep>()
        val turn = CodexAppServerOpenAiSubscription.TurnAccumulator(steps::add)
        val result = async { turn.awaitResult(120) }
        runCurrent()
        repeat(3) {
            advanceTimeBy(90_000)
            turn.textDelta("part")
            runCurrent()
            assertTrue(result.isActive)
        }
        assertEquals("Модель формирует ответ… Получено 12 символов", steps.last().title)
        assertTrue(steps.last().running)
        turn.accept("Complete plan", "final_answer")
        turn.finish(null)
        assertEquals("Complete plan", result.await())
    }

    @Test fun silenceAfterProgressStillTimesOut() = runTest {
        val turn = CodexAppServerOpenAiSubscription.TurnAccumulator {}
        val result = async {
            assertFailsWith<TimeoutCancellationException> { turn.awaitResult(120) }
        }
        runCurrent()
        advanceTimeBy(90_000)
        turn.textDelta("part")
        runCurrent()
        advanceTimeBy(119_999)
        assertTrue(result.isActive)
        advanceTimeBy(1)
        runCurrent()
        result.await()
    }

    @Test fun reasoningRenewsDeadlineWithoutExposingPrivateText() = runTest {
        val steps = mutableListOf<CodingStep>()
        val turn = CodexAppServerOpenAiSubscription.TurnAccumulator(steps::add)
        val result = async { turn.awaitResult(120) }
        runCurrent()
        repeat(3) {
            advanceTimeBy(90_000)
            turn.heartbeat()
            runCurrent()
            assertTrue(result.isActive)
        }
        assertTrue(steps.isEmpty())
        turn.accept("Answer", "final_answer")
        turn.finish(null)
        assertEquals("Answer", result.await())
    }

    @Test fun zeroTimeoutWaitsAndCancellationStopsWait() = runTest {
        val turn = CodexAppServerOpenAiSubscription.TurnAccumulator {}
        val result = async { turn.awaitResult(0) }
        runCurrent()
        advanceTimeBy(3_600_000)
        assertTrue(result.isActive)
        result.cancelAndJoin()
        assertTrue(result.isCancelled)
    }

    @Test fun cancellationAlsoStopsTimedWait() = runTest {
        val turn = CodexAppServerOpenAiSubscription.TurnAccumulator {}
        val result = async { turn.awaitResult(120) }
        runCurrent()
        turn.heartbeat()
        result.cancelAndJoin()
        assertTrue(result.isCancelled)
    }
}
