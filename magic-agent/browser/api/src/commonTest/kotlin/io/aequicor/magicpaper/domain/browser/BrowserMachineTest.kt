package io.aequicor.magicpaper.domain.browser

import kotlin.test.*

class BrowserMachineTest {
    private val owner = BrowserMachine.Owner("session", "request")
    private fun operation(action: BrowserMachine.Action = BrowserMachine.Action.CLICK, id: String = "call") =
        BrowserMachine.Operation(id, action, "0".repeat(64), "tab-1")
    private fun start() = BrowserMachine.reduce(BrowserMachine.initial(), BrowserMachine.Intent.Start(owner)).state
    private fun pending() = BrowserMachine.reduce(start(), BrowserMachine.Intent.Perform(operation())).state
    private fun unknown() = BrowserMachine.reduce(pending(), BrowserMachine.Fact.Failed("call", beforeEffect = false)).state

    @Test fun everyActionAcrossLifecycleAndUnknownStatesHasAnExplicitOutcome() {
        val ready = start()
        val states = listOf(BrowserMachine.initial(), ready, pending(), unknown(),
            BrowserMachine.reduce(ready, BrowserMachine.Intent.Close).state,
            BrowserMachine.reduce(ready, BrowserMachine.Fact.Restored).state,
            BrowserMachine.reduce(ready, BrowserMachine.Fact.PersistenceUnknown).state)
        for (state in states) for (action in BrowserMachine.Action.entries) {
            val transition = BrowserMachine.reduce(state, BrowserMachine.Intent.Perform(operation(action, "next")))
            val allowed = state.lifecycle == BrowserMachine.Stage.READY && !state.persistenceUnknown &&
                (state.unknown.isEmpty() || action.observesOnly)
            assertEquals(allowed, transition.effects.single() is BrowserMachine.Effect.Execute, "$state / $action")
            if (!allowed) assertEquals(state, transition.state)
        }
    }

    @Test fun resultMustMatchTheExecutingOperationAndCannotBeOverwritten() {
        val pending = pending()
        assertIs<BrowserMachine.Effect.Reject>(BrowserMachine.reduce(pending, BrowserMachine.Fact.Completed("foreign", emptySet())).effects.single())
        val completed = BrowserMachine.reduce(pending, BrowserMachine.Fact.Completed("call", setOf("tab-1"))).state
        assertEquals(setOf("tab-1"), completed.tabs)
        assertIs<BrowserMachine.Effect.Reject>(BrowserMachine.reduce(completed, BrowserMachine.Intent.Perform(operation())).effects.single())
        assertIs<BrowserMachine.Effect.Reject>(BrowserMachine.reduce(completed, BrowserMachine.Fact.Failed("call", true)).effects.single())
    }

    @Test fun inspectionAndClosingNeverEraseAnUnknownMutation() {
        val read = BrowserMachine.reduce(unknown(), BrowserMachine.Intent.Perform(operation(BrowserMachine.Action.SNAPSHOT, "inspect"))).state
        val inspected = BrowserMachine.reduce(read, BrowserMachine.Fact.Completed("inspect", setOf("tab-1"))).state
        assertEquals(BrowserMachine.Stage.UNKNOWN, inspected.stage)
        val closing = BrowserMachine.reduce(inspected, BrowserMachine.Intent.Close)
        assertEquals(listOf(BrowserMachine.Effect.Release), closing.effects)
        val closed = BrowserMachine.reduce(closing.state, BrowserMachine.Fact.Closed).state
        assertEquals(BrowserMachine.Stage.UNKNOWN, closed.stage)
        assertTrue(closed.tabs.isEmpty())
        assertIs<BrowserMachine.Effect.Reject>(BrowserMachine.reduce(closed, BrowserMachine.Intent.Perform(operation(id = "next"))).effects.single())
    }

    @Test fun restorationDropsHandlesNeverEmitsWorkAndGivesUnknownPriority() {
        for (action in BrowserMachine.Action.entries) {
            val running = BrowserMachine.reduce(start(), BrowserMachine.Intent.Perform(operation(action))).state
            val restored = BrowserMachine.reduce(running, BrowserMachine.Fact.Restored)
            assertTrue(restored.effects.isEmpty())
            assertNull(restored.state.pending)
            assertTrue(restored.state.tabs.isEmpty())
            assertEquals(if (action.observesOnly) BrowserMachine.Stage.CLOSED else BrowserMachine.Stage.UNKNOWN, restored.state.stage)
        }
    }

    @Test fun missingTabAndPreEffectRejectionDoNotInventAnExternalEffect() {
        for (fact in listOf(BrowserMachine.Fact.NeighbourMissing("call"), BrowserMachine.Fact.Failed("call", true))) {
            val result = BrowserMachine.reduce(pending(), fact)
            assertEquals(BrowserMachine.Stage.READY, result.state.stage)
            assertEquals(setOf("call"), result.state.completed)
            assertTrue(result.effects.isEmpty())
        }
    }

    @Test fun interruptedCloseCannotBeReportedAsConfirmedCleanupOnRestart() {
        val closing = BrowserMachine.reduce(start(), BrowserMachine.Intent.Close).state
        val restored = BrowserMachine.reduce(closing, BrowserMachine.Fact.Restored).state
        assertTrue(restored.cleanupUnknown)
        assertEquals(BrowserMachine.Stage.UNKNOWN, restored.stage)
        val closingAgain = BrowserMachine.reduce(restored, BrowserMachine.Intent.Close).state
        assertEquals(BrowserMachine.Stage.UNKNOWN, BrowserMachine.reduce(closingAgain, BrowserMachine.Fact.Closed).state.stage)
    }
}
