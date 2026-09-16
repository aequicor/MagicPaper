package io.aequicor.magicpaper.ui.components

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatScrollSettleTest {
    @Test fun continuousWheelInputExtendsOneTimerUntilTheLastEventSettles() = runTest {
        val transitions = mutableListOf<Pair<Long, Boolean>>()
        val settle = ChatScrollSettle(backgroundScope, { transitions += testScheduler.currentTime to it },
            testScheduler.timeSource)
        val parent = checkNotNull(backgroundScope.coroutineContext[Job])
        settle.onInput()
        runCurrent()
        val worker = parent.children.single()

        repeat(30) {
            advanceTimeBy(16)
            settle.onInput()
            runCurrent()
            assertSame(worker, parent.children.single(), "Trackpad deltas must retain one settle job")
            assertEquals(listOf(0L to true), transitions, "Continuous input must not reveal message actions mid-gesture")
        }
        advanceTimeBy(119)
        runCurrent()
        assertEquals(listOf(0L to true), transitions)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L to true, 600L to false), transitions)
        assertTrue(worker.isCompleted)
        assertFalse(worker.isCancelled, "Ordinary gesture completion does not cancel a timer or recover its stack trace")

        settle.onInput()
        runCurrent()
        assertNotSame(worker, parent.children.single(), "The next gesture receives a fresh timer")
        assertEquals(600L to true, transitions.last())
        settle.dispose()
        runCurrent()
    }

    @Test fun disposalCancelsPendingSettleAndImmediatelyClearsScrolling() = runTest {
        val transitions = mutableListOf<Boolean>()
        val settle = ChatScrollSettle(backgroundScope, transitions::add, testScheduler.timeSource)
        settle.onInput()
        runCurrent()
        val worker = checkNotNull(backgroundScope.coroutineContext[Job]).children.single()
        advanceTimeBy(40)

        settle.dispose()
        assertEquals(listOf(true, false), transitions, "Leaving the chat clears the transient state immediately")
        runCurrent()
        assertTrue(worker.isCancelled)
        settle.dispose()
        settle.onInput()
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(listOf(true, false), transitions, "A disposed modifier cannot restart or publish a delayed settle")
        assertTrue(checkNotNull(backgroundScope.coroutineContext[Job]).children.none())
    }
}
