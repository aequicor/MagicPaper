package io.aequicor.magicpaper.domain.checks

import io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input
import kotlin.test.*

class CheckPreparationRejectionTest {
    private val command = CheckCommand(CheckRef(CheckScope("project", "session", "request", 2), "call"),
        "/workspace", listOf("build"))
    private val blocked = CheckResult("", null, "Preparation unavailable")

    @Test fun positiveRejectionIsTerminalWithoutAProcessOrCompletionAndReplaysWithoutEffects() {
        val transition = CommandCheckMachine.reduce(CommandCheckMachine.initial(command.resource),
            Input.Fact.PreparationRejected(command, blocked))
        assertTrue(transition.effects.isEmpty())
        val check = transition.state.checks.getValue(command.ref)
        assertEquals(CommandCheckMachine.Phase.FINISHED, check.phase)
        assertEquals(blocked, check.result)
        assertNull(check.process)
        assertNull(check.completion)
        val restored = CommandCheckMachine.reduce(transition.state, Input.Fact.Restored)
        assertEquals(transition.state, restored.state)
        assertTrue(restored.effects.isEmpty())
        assertTrue(CommandCheckMachine.reduce(restored.state, Input.Intent.Submit(command)).effects.isEmpty())
    }

    @Test fun noPreparationRejectionCanOverwriteAnExistingAdmissionOrItsOutcome() {
        val admitted = CommandCheckMachine.reduce(CommandCheckMachine.initial(command.resource), Input.Intent.Submit(command)).state
        val finished = CommandCheckMachine.reduce(CommandCheckMachine.initial(command.resource),
            Input.Fact.PreparationRejected(command, blocked)).state
        val unknown = CommandCheckMachine.reduce(admitted, Input.Fact.Restored).state
        listOf(admitted, finished, unknown).forEach { state ->
            val transition = CommandCheckMachine.reduce(state, Input.Fact.PreparationRejected(command, blocked))
            assertEquals(state, transition.state)
            assertIs<CommandCheckMachine.Effect.Reject>(transition.effects.single())
        }
    }

    @Test fun malformedIdentityForeignResourceAndSuccessShapedEvidenceAreRejected() {
        val initial = CommandCheckMachine.initial(command.resource)
        val inputs = listOf(
            Input.Fact.PreparationRejected(command.copy(workspace = "/other"), blocked),
            Input.Fact.PreparationRejected(command.copy(ref = command.ref.copy(scope = command.ref.scope.copy(generation = -1))), blocked),
            Input.Fact.PreparationRejected(command.copy(ref = command.ref.copy(callId = "")), blocked),
            Input.Fact.PreparationRejected(command.copy(arguments = emptyList()), blocked),
            Input.Fact.PreparationRejected(command, blocked.copy(exitCode = 0)),
            Input.Fact.PreparationRejected(command, blocked.copy(output = "external output")),
            Input.Fact.PreparationRejected(command, blocked.copy(blockedReason = null)),
            Input.Fact.PreparationRejected(command, blocked.copy(binaryOutput = CheckOutputRef("out", 0, "a".repeat(64)))),
        )
        inputs.forEach { input ->
            val transition = CommandCheckMachine.reduce(initial, input)
            assertEquals(initial, transition.state)
            assertIs<CommandCheckMachine.Effect.Reject>(transition.effects.single())
        }
    }

    @Test fun positiveParentEvidencePreservesUnknownSiblingAndPersistenceFences() {
        val sibling = command.copy(ref = command.ref.copy(callId = "child"))
        val admitted = CommandCheckMachine.reduce(CommandCheckMachine.initial(command.resource), Input.Intent.Submit(sibling)).state
        val unknown = CommandCheckMachine.reduce(admitted, Input.Fact.Restored).state
        val transition = CommandCheckMachine.reduce(unknown, Input.Fact.PreparationRejected(command, blocked))
        assertTrue(transition.effects.isEmpty())
        assertTrue(transition.state.unknown)
        assertEquals(unknown.checks[sibling.ref], transition.state.checks[sibling.ref])
        assertEquals(blocked, transition.state.checks[command.ref]?.result)
        val fresh = command.copy(ref = command.ref.copy(callId = "fresh"))
        assertIs<CommandCheckMachine.Effect.Reject>(CommandCheckMachine.reduce(transition.state, Input.Intent.Submit(fresh)).effects.single())
        val corrupt = CommandCheckMachine.reduce(unknown, Input.Fact.PersistenceUnknown).state
        val rejected = CommandCheckMachine.reduce(corrupt, Input.Fact.PreparationRejected(command, blocked))
        assertEquals(corrupt, rejected.state)
        assertIs<CommandCheckMachine.Effect.Reject>(rejected.effects.single())
    }
}
