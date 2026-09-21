package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.tools.ToolRole

/**
 * Mode-dependent authority, separate from a tool's requirements and a run's granted scope.
 * This table does not change the persisted planningMode/researchMode representation.
 */
data class InteractionModeProfile(
    val mode: CodingInteractionMode,
    val orchestrationRoles: Set<ToolRole>,
    val planRoles: Set<ToolRole>,
    val nativeMutationRoles: Set<ToolRole>,
    /** Roles permitted to borrow another mode's history for an auxiliary verification run. */
    val borrowedAuthorityRoles: Set<ToolRole>,
) {
    companion object {
        private val workers = setOf(ToolRole.WORKER, ToolRole.CHAT)
        private val allRoles = setOf(ToolRole.ORCHESTRATOR, ToolRole.PLANNER, ToolRole.WORKER, ToolRole.CHAT)

        /** One complete, printable row per mode; new modes must declare their authority here. */
        val entries: List<InteractionModeProfile> = listOf(
            InteractionModeProfile(
                mode = CodingInteractionMode.CODE,
                orchestrationRoles = emptySet(),
                planRoles = workers,
                nativeMutationRoles = workers,
                borrowedAuthorityRoles = setOf(ToolRole.CHAT),
            ),
            InteractionModeProfile(
                mode = CodingInteractionMode.RESEARCH,
                orchestrationRoles = emptySet(),
                planRoles = workers,
                nativeMutationRoles = emptySet(),
                borrowedAuthorityRoles = emptySet(),
            ),
            InteractionModeProfile(
                mode = CodingInteractionMode.PLANNING,
                orchestrationRoles = allRoles,
                planRoles = allRoles,
                nativeMutationRoles = emptySet(),
                borrowedAuthorityRoles = emptySet(),
            ),
        )

        private val byMode = entries.associateBy { it.mode }

        fun forMode(mode: CodingInteractionMode): InteractionModeProfile = byMode.getValue(mode)
    }
}

val CodingInteractionMode.profile: InteractionModeProfile
    get() = InteractionModeProfile.forMode(this)
