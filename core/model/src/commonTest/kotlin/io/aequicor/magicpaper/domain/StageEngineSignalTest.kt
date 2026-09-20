package io.aequicor.magicpaper.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StageEngineSignalTest {
    private val attempt = StageAttempt("a", "s", StageAssignment("profile", "model"))

    /** Every shape of event the engine can send, so a new one cannot be classified by accident. */
    private val everyEvent = listOf<CodingEvent>(
        CodingEvent.SessionStarted("engine-1"),
        CodingEvent.MessageStarted,
        CodingEvent.TextDelta("фрагмент"),
        CodingEvent.ThinkingDelta("мысль"),
        CodingEvent.FinalThinking("итог мысли"),
        CodingEvent.FinalText("ответ"),
        CodingEvent.ToolStarted("shell", "rm -rf build", isExec = true),
        CodingEvent.ToolFinished("shell", isError = false, resultPreview = "готово"),
        CodingEvent.ToolProgress("shell", resultPreview = "идёт"),
        CodingEvent.OutputTruncated(120, 90),
        CodingEvent.Notice("уплотнение контекста"),
        CodingEvent.Notice(""),
        CodingEvent.UsageObserved(TokenUsage(), "src"),
        CodingEvent.ModelRequest("req"),
        CodingEvent.ContextUpdated(1, 2),
        CodingEvent.SearchObserved("s"),
        CodingEvent.AgentEnd,
        CodingEvent.Failed("сбой"),
        CodingEvent.Finished,
    )

    @Test fun everyEventThatChangesRecoveryStateMustReachDisk() {
        // This is the whole point of the classification. A record the loop keeps in memory
        // until the next tick is a record a stop can erase — and an erased pending command
        // is an external effect that would be repeated with nobody knowing its outcome.
        everyEvent.forEach { event ->
            val before = attempt.copy(engineSessionId = "engine-0", pendingTool = "прежняя", pendingToolExternal = true)
            val after = before.after(event, StageRunTrack.WORK)
            val changedRecovery = after.resumption != before.resumption || after.engineSessionId != before.engineSessionId
            if (changedRecovery) assertTrue(event.signal.mustReachDisk, "$event меняет восстановление, но не сохраняется")
        }
    }

    @Test fun onlyStreamedTextAndSilenceStayOffTheScreen() {
        everyEvent.forEach { event ->
            val quiet = event is CodingEvent.TextDelta || (event is CodingEvent.Notice && event.message.isBlank())
            assertEquals(!quiet, event.signal.showsProgress, "$event")
        }
    }

    @Test fun silenceChangesNothingAboutTheAttempt() {
        val busy = attempt.copy(report = "ответ", activity = "shell: сборка", pendingTool = "сборка", pendingToolExternal = true)
        StageRunTrack.entries.forEach { assertEquals(busy, busy.after(CodingEvent.Notice(""), it), it.name) }
        assertEquals(StageEngineSignal.Silence, CodingEvent.Notice("").signal)
        assertFalse(StageEngineSignal.Silence.showsProgress)
        assertFalse(StageEngineSignal.Silence.mustReachDisk)
    }

    @Test fun fragmentsAccumulateAndTheFinalTextReplacesThem() {
        StageRunTrack.entries.forEach { track ->
            val streamed = attempt.after(CodingEvent.TextDelta("Часть "), track).after(CodingEvent.TextDelta("вторая"), track)
            assertEquals("Часть вторая", streamed.report(track), track.name)
            // The engine repeats the whole answer at the end; appending it would double it.
            assertEquals("Ответ", streamed.after(CodingEvent.FinalText("Ответ"), track).report(track), track.name)
        }
    }

    @Test fun aTrackWritesItsOwnFieldsAndLeavesTheOtherAlone() {
        val work = attempt.after(CodingEvent.SessionStarted("w"), StageRunTrack.WORK)
            .after(CodingEvent.FinalText("работа"), StageRunTrack.WORK)
        assertEquals("работа" to "w", work.report to work.engineSessionId)
        assertEquals("" to "", work.mergeReport to work.mergeEngineSessionId)

        val merge = attempt.after(CodingEvent.SessionStarted("m"), StageRunTrack.MERGE)
            .after(CodingEvent.FinalText("слияние"), StageRunTrack.MERGE)
        assertEquals("слияние" to "m", merge.mergeReport to merge.mergeEngineSessionId)
        assertEquals("" to "", merge.report to merge.engineSessionId)
    }

    @Test fun anExternalCommandIsPendingUntilItsToolReports() {
        StageRunTrack.entries.forEach { track ->
            val running = attempt.after(CodingEvent.ToolStarted("shell", "git push", isExec = true), track)
            assertEquals(StageResumption.UnknownOutcome("git push"), running.resumption, track.name)
            val settled = running.after(CodingEvent.ToolFinished("shell", isError = false, resultPreview = "ok"), track)
            assertEquals(StageResumption.Runnable(AttemptPhase.PREPARED), settled.resumption, track.name)
        }
    }

    @Test fun aLocalCommandLeavesNothingToReconcile() {
        // Reading a file has no consequence to confirm, so stopping mid-command may resume.
        val reading = attempt.after(CodingEvent.ToolStarted("read", "src/main.kt"), StageRunTrack.WORK)
        assertEquals(StageResumption.Runnable(AttemptPhase.PREPARED), reading.resumption)
        assertTrue(reading.pendingTool.isNotBlank(), "Команда всё же показывается как текущая")
    }

    @Test fun aRunIsUsableOnlyWhenItFinishedAndSaidSomething() {
        val finished = StageRunResult().after(CodingEvent.Finished)
        assertFalse(finished.incomplete("отчёт"))
        assertTrue(finished.incomplete(""), "Молчаливый прогон нечего принимать")
        assertTrue(StageRunResult().incomplete("отчёт"), "Оборванный поток не подтверждает результат")
        val failed = finished.after(CodingEvent.Failed("сбой сети"))
        assertTrue(failed.incomplete("отчёт"))
        assertEquals("сбой сети", failed.failure)
    }

    @Test fun onlyRealAnswersCountAsTheEngineAnswering() {
        everyEvent.forEach {
            val answering = it is CodingEvent.SessionStarted || it is CodingEvent.MessageStarted ||
                it is CodingEvent.TextDelta || it is CodingEvent.FinalText
            assertEquals(answering, it.showsEngineAnswering, "$it")
        }
    }
}
