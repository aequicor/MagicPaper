package io.aequicor.magicpaper.domain

/**
 * Which of an attempt's two runs an engine event belongs to.
 *
 * An attempt runs the work itself and, separately, the merge of its result. Both stream the
 * same [CodingEvent]s and both fill the same attempt, but into a different pair of fields.
 * This is the only difference between them, so it is the only thing the caller states.
 */
enum class StageRunTrack { WORK, MERGE }

/**
 * What one engine event means for the run that is streaming it.
 *
 * The service used to answer this twice per event with two separate literal lists of event
 * types — one for the live view, one for the save — repeated at four call sites. The two
 * questions are genuinely different and stay separate here, but each is now asked once.
 *
 * [Recorded] is not a display concern. It marks the events that change what recovery will
 * read: an engine session id it must reuse, and the pending-command pair that decides
 * [StageResumption.UnknownOutcome]. Losing one of those is not a dropped frame — it is an
 * effect whose outcome nobody will be able to reconstruct.
 */
sealed interface StageEngineSignal {
    /** The engine said nothing. A chance to flush what is pending, never new output. */
    data object Silence : StageEngineSignal

    /** A fragment of the answer. It arrives too often to show every time. */
    data object Streaming : StageEngineSignal

    /** A visible step. Worth showing at once; the run survives losing it. */
    data object Step : StageEngineSignal

    /** The attempt's recovery record changed and must reach disk. */
    data object Recorded : StageEngineSignal
}

val CodingEvent.signal: StageEngineSignal
    get() = when (this) {
        is CodingEvent.Notice -> if (message.isBlank()) StageEngineSignal.Silence else StageEngineSignal.Step
        is CodingEvent.TextDelta -> StageEngineSignal.Streaming
        is CodingEvent.SessionStarted, is CodingEvent.ToolStarted, is CodingEvent.ToolFinished -> StageEngineSignal.Recorded
        else -> StageEngineSignal.Step
    }

/** True when the viewer should see this event now rather than at the next tick. */
val StageEngineSignal.showsProgress: Boolean
    get() = this != StageEngineSignal.Silence && this != StageEngineSignal.Streaming

/** True when the event must be persisted before the next one, not on the next tick. */
val StageEngineSignal.mustReachDisk: Boolean get() = this == StageEngineSignal.Recorded

/** True while the engine is demonstrably answering, as opposed to merely connected. */
val CodingEvent.showsEngineAnswering: Boolean
    get() = this is CodingEvent.SessionStarted || this is CodingEvent.MessageStarted ||
        this is CodingEvent.TextDelta || this is CodingEvent.FinalText

/** How much of a tool's output the attempt keeps as its current activity. */
private const val ACTIVITY_PREVIEW = 1500

/** The report this track writes into: the attempt's own, or the one for its merge. */
fun StageAttempt.report(track: StageRunTrack): String = when (track) {
    StageRunTrack.WORK -> report
    StageRunTrack.MERGE -> mergeReport
}

private fun StageAttempt.withReport(track: StageRunTrack, text: String): StageAttempt = when (track) {
    StageRunTrack.WORK -> copy(report = text)
    StageRunTrack.MERGE -> copy(mergeReport = text)
}

/**
 * Applies one engine event to the attempt.
 *
 * `TextDelta` appends and `FinalText` replaces: the engine sends the authoritative text once
 * at the end, and treating it as another fragment would double the answer.
 *
 * `ToolStarted` and `ToolFinished` move [StageAttempt.pendingTool] and
 * [StageAttempt.pendingToolExternal] together, so a stop between them leaves exactly the
 * state [StageResumption] reads. They are a pair; no caller may write one without the other.
 */
fun StageAttempt.after(event: CodingEvent, track: StageRunTrack): StageAttempt = when (event) {
    is CodingEvent.SessionStarted -> when (track) {
        StageRunTrack.WORK -> copy(engineSessionId = event.sessionId)
        StageRunTrack.MERGE -> copy(mergeEngineSessionId = event.sessionId)
    }
    is CodingEvent.TextDelta -> withReport(track, report(track) + event.delta)
    is CodingEvent.FinalText -> withReport(track, event.text)
    is CodingEvent.ToolStarted -> copy(
        activity = "${event.tool}: ${event.summary}",
        pendingTool = event.summary,
        pendingToolExternal = event.isExec && !PlanningRetryPolicy.localCheck(event.summary),
    )
    is CodingEvent.ToolFinished -> copy(
        activity = "${event.tool}: ${event.resultPreview.take(ACTIVITY_PREVIEW)}",
        pendingTool = "",
        pendingToolExternal = false,
    )
    is CodingEvent.Notice -> if (event.message.isBlank()) this else copy(activity = event.message)
    else -> this
}

/**
 * How an engine run ended, accumulated from its own events.
 *
 * The run is only usable when the engine both finished and reported no failure. Each of the
 * four run sites kept this in two local flags; keeping them in one value is what stops a site
 * from checking one and forgetting the other.
 */
data class StageRunResult(val failure: String? = null, val ended: Boolean = false) {
    fun after(event: CodingEvent): StageRunResult = when (event) {
        is CodingEvent.Failed -> copy(failure = event.message)
        CodingEvent.Finished -> copy(ended = true)
        else -> this
    }

    /** True when nothing usable came out: it failed, it stopped early, or it said nothing. */
    fun incomplete(report: String): Boolean = failure != null || !ended || report.isBlank()
}
