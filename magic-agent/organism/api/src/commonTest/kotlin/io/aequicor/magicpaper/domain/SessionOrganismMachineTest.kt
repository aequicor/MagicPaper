package io.aequicor.magicpaper.domain

import kotlin.test.*

class SessionOrganismMachineTest {
    private fun stamp(id: String) = SessionOrganismMachine.Stamp(id, 1000)
    private fun seed(): SessionOrganismMachine.State {
        val root = CodingSession("root", "project", "Root", 1, researchMode = true)
        return SessionOrganismMachine.reduce(SessionOrganismMachine.initial("root"),
            SessionOrganismMachine.Fact.Adopt(stamp("adopt"), "project", root, emptyList(), OrganismLimits(tokens = 1000, recoveryTokens = 100))).state
    }
    @Test fun deterministicInputCreatesTheSameAtomicAllocationAndReceipt() {
        val state = seed()
        val root = state.organism!!.sessions.getValue("root")
        val input = SessionOrganismMachine.Intent.Command(stamp("command"), SessionAuthority("project", "root", "root", root.generation, root.mode),
            "create", OrganismCommand(OrganismAction.CREATE, name = "Child", tokens = 200,
                task = SessionTask("Work", "root", "Result")), "fingerprint")
        val first = SessionOrganismMachine.reduce(state, input)
        assertNull(first.reject)
        assertEquals(first, SessionOrganismMachine.reduce(state, input))
        val saved = first.state.organism!!
        assertEquals(1, saved.operations.size)
        assertEquals(state.organism!!.sessions.values.sumOf { it.remainingTokens }, saved.sessions.values.sumOf { it.remainingTokens })
        assertEquals(200L, saved.sessions.getValue("session-create").remainingTokens)
        val again = SessionOrganismMachine.reduce(first.state, input)
        assertEquals(first.state, again.state)
        assertFalse(again.outputs.filterIsInstance<SessionOrganismMachine.Output.CommandAccepted>().single().fresh)
    }
    @Test fun wrongOwnerAndUnknownPersistenceNeverProduceAuthority() {
        val state = seed()
        val wrong = SessionOrganismMachine.reduce(state, SessionOrganismMachine.Intent.BeginRun(stamp("bad"), "other", "root"))
        assertNotNull(wrong.reject); assertEquals(state, wrong.state); assertTrue(wrong.outputs.isEmpty())
        val unknown = SessionOrganismMachine.reduce(state, SessionOrganismMachine.Fact.PersistenceUnknown(stamp("unknown"))).state
        val start = SessionOrganismMachine.reduce(unknown, SessionOrganismMachine.Intent.BeginRun(stamp("start"), "root", "root"))
        assertEquals(SessionOrganismMachine.Rejection.UNKNOWN, start.reject?.kind)
        assertTrue(start.outputs.isEmpty())
    }
    @Test fun restoredRunningGenerationBecomesUnknownWithoutNativeEffects() {
        val running = SessionOrganismMachine.reduce(seed(), SessionOrganismMachine.Intent.BeginRun(stamp("start"), "root", "root")).state
        val restored = SessionOrganismMachine.reduce(running, SessionOrganismMachine.Fact.Restored(stamp("restore"), "root"))
        assertTrue(restored.outputs.isEmpty())
        assertEquals(SessionObservedState.UNKNOWN, restored.state.organism!!.sessions.getValue("root").observed)
        assertFalse(restored.state.organism!!.sessions.getValue("root").acceptsWork)
    }
}
