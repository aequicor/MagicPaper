package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.CodingInteractionMode
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class InteractionModeAccessTest {
    private val contexts = buildList {
        for (mode in CodingInteractionMode.entries) for (role in ToolRole.entries)
            for (auxiliary in listOf(false, true)) for (plan in listOf(null, "plan"))
                for (stage in listOf(null, "stage")) for (worktree in listOf(null, "worktree"))
                    add(ToolExecutionContext("project", "owner", "session", "request", role, mode,
                        planId = plan, stageId = stage, auxiliaryExecution = auxiliary, taskWorktreeId = worktree))
    }

    @Test fun catalogMatchesTheOriginalPredicateAcrossEveryModeRoleAndAuthority() {
        for (definition in ToolCatalog.definitions) for (context in contexts) {
            assertEquals(definition.originalAllowed(context), definition.allowed(context),
                "${definition.id}: $context")
            for (ownerMode in CodingInteractionMode.entries) {
                val expected = context.mode == ownerMode || (context.auxiliaryExecution && context.role == ToolRole.CHAT &&
                    context.mode == CodingInteractionMode.CODE && context.planId != null && !definition.native &&
                    (!definition.mutating || definition.id == "immunity.signal"))
                assertEquals(expected, definition.allowsAuthorityMode(context, ownerMode),
                    "${definition.id}: $context, ownerMode=$ownerMode")
            }
        }
    }

    /**
     * Catalog entries do not exercise every combination, and external tools use the same policy.
     * Enumerate every requirement flag and both membership outcomes for each role/mode. Set
     * contents other than the current role/mode are irrelevant to access, so empty/all is total.
     */
    @Test fun uncataloguedDefinitionsMatchThePreTablePolicyForEveryRequirementCombination() {
        for (mask in 0 until 256) {
            fun flag(bit: Int) = mask and (1 shl bit) != 0
            val definition = ToolDefinition(
                id = "external.tool", description = "External tool", schema = JsonObject(emptyMap()),
                native = flag(0), mutating = flag(1), needsPlan = flag(2), orchestration = flag(3),
                needsTaskWorktree = flag(4), auxiliarySafe = flag(5),
                roles = if (flag(6)) ToolRole.entries.toSet() else emptySet(),
                modes = if (flag(7)) CodingInteractionMode.entries.toSet() else emptySet(),
            )
            for (context in contexts) {
                val expected = (!definition.orchestration || context.mode == CodingInteractionMode.PLANNING) &&
                    context.role in definition.roles && (!definition.needsPlan || context.planId != null) &&
                    (!context.auxiliaryExecution || definition.native || !definition.mutating || definition.auxiliarySafe) &&
                    (!definition.needsPlan || context.role !in setOf(ToolRole.ORCHESTRATOR, ToolRole.PLANNER) ||
                        context.mode == CodingInteractionMode.PLANNING) && context.mode in definition.modes &&
                    (!definition.needsTaskWorktree || (context.taskWorktreeId != null && context.stageId == null)) &&
                    (!definition.native || !definition.mutating || (context.mode == CodingInteractionMode.CODE &&
                        context.role in setOf(ToolRole.WORKER, ToolRole.CHAT)))
                assertEquals(expected, definition.allowed(context), "requirements=$mask: $context")
                for (ownerMode in CodingInteractionMode.entries) {
                    val authorityExpected = context.mode == ownerMode || (context.auxiliaryExecution &&
                        context.role == ToolRole.CHAT && context.mode == CodingInteractionMode.CODE &&
                        context.planId != null && !definition.native && (!definition.mutating || definition.auxiliarySafe))
                    assertEquals(authorityExpected, definition.allowsAuthorityMode(context, ownerMode),
                        "requirements=$mask: $context, ownerMode=$ownerMode")
                }
            }
        }
    }
}

/** Frozen predicate from before access rules moved to definition data and mode profiles. */
private fun ToolDefinition.originalAllowed(context: ToolExecutionContext): Boolean =
    (!orchestration || context.mode == CodingInteractionMode.PLANNING) && context.role in roles && (!needsPlan || context.planId != null) &&
        (!context.auxiliaryExecution || native || !mutating || id == "immunity.signal") &&
        (!needsPlan || context.role !in setOf(ToolRole.ORCHESTRATOR, ToolRole.PLANNER) || context.mode == CodingInteractionMode.PLANNING) &&
        (id != "stage.handoff" || context.mode == CodingInteractionMode.CODE) &&
        (id != "task.handoff" || (context.taskWorktreeId != null && context.mode == CodingInteractionMode.CODE && context.stageId == null)) &&
        (!native || !mutating || (context.mode == CodingInteractionMode.CODE && context.role in setOf(ToolRole.WORKER, ToolRole.CHAT))) &&
        (id != "research_check" || context.mode == CodingInteractionMode.RESEARCH) &&
        (id !in setOf("image.generate", "video.generate") || context.mode != CodingInteractionMode.PLANNING)
