package io.aequicor.magicpaper.domain.checks

import kotlin.test.*

class CheckGitReadAdmissionTest {
    @Test fun onlyFixedReadQueriesCanUseReadOnlyBinaryGrant() {
        val ref = CheckRef(CheckScope("p", "s", "r", 0), "read")
        for (query in CheckGitReadQuery.entries) {
            val command = CheckCommand(ref, "/source", query.arguments(), policy = CheckPolicy.GIT_READ_ONLY,
                outputMode = CheckOutputMode.BINARY_STDOUT)
            val state = CommandCheckMachine.initial("/source")
            assertTrue(CommandCheckMachine.reduce(state, CommandCheckMachine.Input.Intent.Submit(command)).effects.single() is CommandCheckMachine.Effect.Prepare)
            for (invalid in listOf(command.copy(arguments = command.arguments + "--output=/source/file"),
                command.copy(environment = mapOf("GIT_INDEX_FILE" to "/source/index")),
                command.copy(arguments = listOf("git", "reset", "--hard")))) {
                assertTrue(CommandCheckMachine.reduce(state, CommandCheckMachine.Input.Intent.Submit(invalid)).effects.single() is CommandCheckMachine.Effect.Reject)
            }
        }
    }
}
