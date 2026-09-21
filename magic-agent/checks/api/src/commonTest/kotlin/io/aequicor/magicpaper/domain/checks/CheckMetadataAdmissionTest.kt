package io.aequicor.magicpaper.domain.checks

import io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Effect
import io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input
import io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Reason
import kotlin.test.*

class CheckMetadataAdmissionTest {
    private val parent = CheckRef(CheckScope("project", "session", "request", 3), "parent")
    private val source = CheckMetadataSource(parent, CheckGitMetadataQuery.TRACKED_FILES)
    private val child = CheckCommand(parent.copy(callId = "child"), "/project", source.query.arguments(),
        policy = CheckPolicy.METADATA_READ_ONLY, outputMode = CheckOutputMode.BINARY_STDOUT,
        protectedResource = "/project", metadataSource = source)

    @Test fun metadataAdmissionRequiresExactParentResourceAndFixedReadOnlyCommand() {
        val state = CommandCheckMachine.initial("/project")
        assertIs<Effect.Prepare>(CommandCheckMachine.reduce(state, Input.Intent.Submit(child)).effects.single())
        listOf(
            child.copy(ref = parent), child.copy(protectedResource = null), child.copy(protectedResource = "/other"),
            child.copy(workspace = "/other"), child.copy(metadataSource = null),
            child.copy(metadataSource = source.copy(parent = parent.copy(scope = parent.scope.copy(generation = 4)))),
            child.copy(arguments = listOf("git", "add", "-A")), child.copy(outputMode = CheckOutputMode.TEXT),
            child.copy(environment = mapOf("GIT_INDEX_FILE" to "/other/index")),
            child.copy(policy = CheckPolicy.MANAGED_WORKTREE),
        ).forEach {
            assertEquals(listOf(Effect.Reject(Reason.INVALID)), CommandCheckMachine.reduce(state, Input.Intent.Submit(it)).effects)
        }
    }

    @Test fun restoredUnknownChildBlocksBothItsParentAndAnotherRequestWithoutEffects() {
        val submitted = CommandCheckMachine.reduce(CommandCheckMachine.initial("/project"), Input.Intent.Submit(child)).state
        val restored = CommandCheckMachine.reduce(submitted, Input.Fact.Restored)
        assertTrue(restored.effects.isEmpty())
        assertEquals(source, restored.state.checks.getValue(child.ref).command.metadataSource)
        listOf(parent, parent.copy(scope = parent.scope.copy(requestId = "fresh"))).forEach { ref ->
            val command = CheckCommand(ref, "/project", listOf("build"))
            assertEquals(listOf(Effect.Reject(Reason.UNKNOWN)), CommandCheckMachine.reduce(restored.state, Input.Intent.Submit(command)).effects)
        }
    }
}
