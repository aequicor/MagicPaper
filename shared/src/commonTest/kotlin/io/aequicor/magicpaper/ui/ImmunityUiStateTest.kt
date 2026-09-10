package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import kotlin.test.*

class ImmunityUiStateTest {
    @Test fun savedProposalMarksItsImmunityAndProjectWaitingUntilResolved() {
        val proposal = ImmunityIntervention("proposal", "signal", "root", 1, setOf(ImmunityAction.STOP),
            listOf("Остановка не подтверждена"), setOf("root"), 1)
        val organism = SessionOrganism("organism", "project", "root", "immunity", 1, interventions = listOf(proposal))
        val ui = CodingUi(organisms = mapOf(organism.id to organism), sessions = listOf(
            CodingSessionUi(CodingSession("root", "project", "Корень", 1, organismId = organism.id)),
            CodingSessionUi(CodingSession("immunity", "project", "Иммунитет", 1, organismId = organism.id))))
        assertEquals(CodingSessionStatus.WAITING, ui.statusOf("project"))
        assertEquals(CodingSessionStatus.WAITING, ui.sessionsOf("project").single { it.session.id == "immunity" }.status)
        assertEquals(CodingSessionStatus.IDLE, ui.sessionsOf("project").single { it.session.id == "root" }.status)
        val dismissed = ui.copy(organisms = mapOf(organism.id to organism.copy(interventions = listOf(proposal.copy(state = ImmunityInterventionState.REJECTED)))))
        assertEquals(CodingSessionStatus.IDLE, dismissed.statusOf("project"))
        assertEquals(CodingSessionStatus.IDLE, dismissed.sessionsOf("project").single { it.session.id == "immunity" }.status)
    }
}
