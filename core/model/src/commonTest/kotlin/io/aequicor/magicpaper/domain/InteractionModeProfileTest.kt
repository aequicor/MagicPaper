package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.tools.ToolRole
import kotlin.test.Test
import kotlin.test.assertEquals

class InteractionModeProfileTest {
    @Test fun everyModeHasExactlyOneProfile() {
        assertEquals(CodingInteractionMode.entries.toList(), InteractionModeProfile.entries.map { it.mode })
        for (entry in InteractionModeProfile.entries) assertEquals(entry, entry.mode.profile)
    }

    @Test fun authorityTableMatchesTheRecordedPolicy() {
        val actual = InteractionModeProfile.entries.joinToString("\n") { profile ->
            fun Set<ToolRole>.names() = ToolRole.entries.filter { it in this }.joinToString(",").ifEmpty { "-" }
            "${profile.mode}: orchestration=${profile.orchestrationRoles.names()}; plan=${profile.planRoles.names()}; " +
                "nativeMutation=${profile.nativeMutationRoles.names()}; borrow=${profile.borrowedAuthorityRoles.names()}"
        }
        assertEquals(
            """
                CODE: orchestration=-; plan=WORKER,CHAT; nativeMutation=WORKER,CHAT; borrow=CHAT
                RESEARCH: orchestration=-; plan=WORKER,CHAT; nativeMutation=-; borrow=-
                PLANNING: orchestration=ORCHESTRATOR,PLANNER,WORKER,CHAT; plan=ORCHESTRATOR,PLANNER,WORKER,CHAT; nativeMutation=-; borrow=-
            """.trimIndent(), actual,
        )
    }
}
