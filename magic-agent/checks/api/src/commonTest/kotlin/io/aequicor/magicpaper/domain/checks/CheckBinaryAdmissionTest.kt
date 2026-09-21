package io.aequicor.magicpaper.domain.checks

import io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Effect
import io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input
import io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Reason
import kotlin.test.*

class CheckBinaryAdmissionTest {
    private val ref = CheckRef(CheckScope("project", "session", "request", 0), "call")
    private val command = CheckCommand(ref, "/workspace", listOf("git", "status", "--porcelain=v1", "-z"),
        policy = CheckPolicy.MANAGED_WORKTREE, outputMode = CheckOutputMode.BINARY_STDOUT,
        environment = mapOf("GIT_TERMINAL_PROMPT" to "0", "GIT_INDEX_FILE" to "/private/index"))

    @Test fun binaryAndEnvironmentOverridesRequireTheManagedPolicyAndAllowlistedValues() {
        val initial = CommandCheckMachine.initial(command.workspace)
        assertIs<Effect.Prepare>(CommandCheckMachine.reduce(initial, Input.Intent.Submit(command)).effects.single())
        listOf(
            command.copy(policy = CheckPolicy.PROTECTED_PROJECT),
            command.copy(environment = mapOf("PATH" to "/other")),
            command.copy(environment = mapOf("GIT_CONFIG_GLOBAL" to "/other")),
            command.copy(environment = mapOf("GIT_TERMINAL_PROMPT" to "1")),
            command.copy(environment = mapOf("GIT_INDEX_FILE" to "bad\u0000path")),
            command.copy(environment = mapOf("GIT_INDEX_FILE" to "")),
        ).forEach {
            assertEquals(listOf(Effect.Reject(Reason.INVALID)), CommandCheckMachine.reduce(initial, Input.Intent.Submit(it)).effects)
        }
    }

    @Test fun admittedEnvironmentIsFrozenAndPartOfTheExactCommandIdentity() {
        val environment = command.environment.toMutableMap()
        var state = CommandCheckMachine.reduce(CommandCheckMachine.initial(command.workspace),
            Input.Intent.Submit(command.copy(environment = environment))).state
        environment["GIT_INDEX_FILE"] = "/changed"
        assertEquals(command.environment, state.checks.getValue(ref).command.environment)
        val receipt = CheckProcessReceipt("receipt", "controlled", 123)
        val result = CheckResult("stderr", 0, binaryOutput = CheckOutputRef(receipt.id, 4, "a".repeat(64)))
        listOf(Input.Fact.ProcessPrepared(ref, receipt), Input.Intent.Release(ref, receipt.id),
            Input.Fact.Exited(ref, receipt.id, result), Input.Fact.GroupStopped(ref, receipt.id, "group"),
            Input.Fact.AuthorityRestored(ref, receipt.id, "authority"), Input.Fact.ArtifactsCommitted(ref, receipt.id, "artifact")
        ).forEach { state = CommandCheckMachine.reduce(state, it).also { transition ->
            assertTrue(transition.effects.none { effect -> effect is Effect.Reject })
        }.state }
        listOf(command.copy(environment = environment), command.copy(outputMode = CheckOutputMode.TEXT)).forEach {
            assertEquals(listOf(Effect.Reject(Reason.PAYLOAD_CHANGED)), CommandCheckMachine.reduce(state, Input.Intent.Submit(it)).effects)
        }
        val replay = CommandCheckMachine.reduce(state, Input.Fact.Restored)
        assertTrue(replay.effects.isEmpty())
        assertEquals(result, replay.state.checks.getValue(ref).result)
    }

    @Test fun foreignOrMalformedOutputCannotBecomeACompletionProof() {
        val receipt = CheckProcessReceipt("receipt", "controlled", 123)
        var running = CommandCheckMachine.initial(command.workspace)
        listOf(Input.Intent.Submit(command), Input.Fact.ProcessPrepared(ref, receipt), Input.Intent.Release(ref, receipt.id))
            .forEach { running = CommandCheckMachine.reduce(running, it).state }
        val restored = CommandCheckMachine.reduce(running, Input.Fact.Restored).state
        val correct = CheckOutputRef(receipt.id, 4, "a".repeat(64))
        val proof = CheckCompletionProof(receipt.id, "group", "authority", "artifact")
        listOf(null, correct.copy(id = "foreign"), correct.copy(bytes = -1),
            correct.copy(bytes = CheckOutputRef.MAX_BYTES + 1), correct.copy(digest = "z".repeat(64))).forEach { output ->
            val result = CheckResult("stderr", 0, binaryOutput = output)
            assertIs<Effect.Reject>(CommandCheckMachine.reduce(running, Input.Fact.Exited(ref, receipt.id, result)).effects.single())
            assertIs<Effect.Reject>(CommandCheckMachine.reduce(restored, Input.Fact.CompletionRecovered(ref, proof, result)).effects.single())
        }
    }
}
