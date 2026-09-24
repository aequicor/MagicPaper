package io.aequicor.magicpaper

import io.aequicor.magicpaper.designsystem.PaperActivityTone
import io.aequicor.magicpaper.domain.CodingSessionStatus
import io.aequicor.magicpaper.ui.screens.activityTone
import io.aequicor.magicpaper.ui.screens.aggregateDockTone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The dock's single dot is a workspace summary, and its precedence is the reader's first cue:
 * a run in progress outranks a question, a question outranks unread results, and only a
 * workspace with none of those is green.
 */
class AgentDockToneTest {
    private fun toneOf(vararg statuses: CodingSessionStatus) =
        aggregateDockTone(statuses.map { it.activityTone })

    @Test fun oneWorkingSessionOutranksQuestionsAndUnread() {
        assertEquals(PaperActivityTone.WORKING, toneOf(
            CodingSessionStatus.UNREAD, CodingSessionStatus.WAITING, CodingSessionStatus.WORKING))
    }

    @Test fun aQuestionOutranksUnreadButYieldsToWork() {
        assertEquals(PaperActivityTone.ATTENTION, toneOf(
            CodingSessionStatus.UNREAD, CodingSessionStatus.WAITING))
        assertEquals(PaperActivityTone.ATTENTION, toneOf(
            CodingSessionStatus.CONFIRMATION, CodingSessionStatus.BLOCKED))
    }

    @Test fun unreadOutranksAQuietWorkspace() {
        assertEquals(PaperActivityTone.UNREAD, toneOf(
            CodingSessionStatus.IDLE, CodingSessionStatus.UNREAD))
    }

    @Test fun aWorkspaceWithoutWorkQuestionsOrUnreadIsGreen() {
        assertEquals(PaperActivityTone.READY, toneOf(
            CodingSessionStatus.IDLE, CodingSessionStatus.NEEDS_TESTING,
            CodingSessionStatus.QUEUED, CodingSessionStatus.SCHEDULED))
    }
}
