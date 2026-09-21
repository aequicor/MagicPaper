package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.*
import kotlin.test.*

class PiAttemptEventsTest {
    @Test fun providerFailureIsNotAnAnswerAndIsEmittedOnlyAfterRetriesFinish() {
        val attempt = PiAttemptEvents()
        val error = CodingEvent.Failed("provider unavailable")
        assertNull(attempt.accept(error))
        attempt.accept(CodingEvent.AgentEnd)
        assertFalse(attempt.answerSeen)
        assertEquals(error, attempt.failure)
    }

    @Test fun successfulNativeRetryDoesNotPoisonTheCompletedResearch() {
        val attempt = PiAttemptEvents()
        val recorder = CodingRunRecorder()
        listOf(CodingEvent.MessageStarted, CodingEvent.Failed("temporary failure"),
            CodingEvent.Notice("retrying"), CodingEvent.MessageStarted,
            CodingEvent.FinalText("Verified answer"), CodingEvent.AgentEnd).forEach { event ->
            attempt.accept(event)?.let(recorder::apply)
        }
        assertTrue(attempt.answerSeen)
        assertNull(attempt.failure)
        assertFalse(recorder.message("answer", 1).failed)
    }

    @Test fun aLaterFailureStillFailsEvenIfAnEarlierMessageContainedText() {
        val attempt = PiAttemptEvents()
        attempt.accept(CodingEvent.FinalText("I will check the sources"))
        val terminal = CodingEvent.Failed("provider refused the final answer")
        attempt.accept(terminal)
        assertEquals(terminal, attempt.failure)
    }

    @Test fun failedPageToolDoesNotFailAnOtherwiseSuccessfulAttempt() {
        val attempt = PiAttemptEvents()
        val recorder = CodingRunRecorder()
        listOf(CodingEvent.Failed("temporary model failure"),
            CodingEvent.ToolStarted("read", "", "source"), CodingEvent.ToolFinished("read", true, "source"),
            CodingEvent.FinalText("Answer from another source")).forEach { event ->
            attempt.accept(event)?.let(recorder::apply)
        }
        assertNull(attempt.failure)
        assertFalse(recorder.message("answer", 1).failed)
    }
}
