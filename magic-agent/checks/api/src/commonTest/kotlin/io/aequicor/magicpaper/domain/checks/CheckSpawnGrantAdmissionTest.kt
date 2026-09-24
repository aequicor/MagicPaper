package io.aequicor.magicpaper.domain.checks

import kotlin.test.*

/** The user's program-start grant widens only a managed-worktree text check; no other policy can carry it. */
class CheckSpawnGrantAdmissionTest {
    private val state = CommandCheckMachine.initial("/task")
    private val granted = CheckCommand(CheckRef(CheckScope("p", "s", "r", 0), "check"), "/task", listOf("./gradlew", "check"),
        policy = CheckPolicy.MANAGED_WORKTREE, spawnGranted = true)
    private fun admitted(command: CheckCommand) =
        CommandCheckMachine.reduce(state, CommandCheckMachine.Input.Intent.Submit(command)).effects.single() is CommandCheckMachine.Effect.Prepare

    @Test fun managedWorktreeCheckMayBeGranted() = assertTrue(admitted(granted))

    @Test fun grantOnAnyOtherPolicyOrOutputIsRefused() {
        assertFalse(admitted(granted.copy(policy = CheckPolicy.PROTECTED_PROJECT)))
        assertFalse(admitted(granted.copy(outputMode = CheckOutputMode.BINARY_STDOUT)))
        val read = CheckGitReadQuery.HEAD.arguments()
        assertFalse(admitted(granted.copy(arguments = read, policy = CheckPolicy.GIT_READ_ONLY, outputMode = CheckOutputMode.BINARY_STDOUT)))
    }
}
