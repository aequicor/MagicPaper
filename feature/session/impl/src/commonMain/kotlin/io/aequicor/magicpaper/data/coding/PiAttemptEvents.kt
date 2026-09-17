package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.CodingEvent

/** Pi reports failed model messages before its own retry. Only an unrecovered
 * failure at process completion is terminal for the application recorder. */
internal class PiAttemptEvents {
    var answerSeen = false
        private set
    var failure: CodingEvent.Failed? = null
        private set

    fun accept(event: CodingEvent): CodingEvent? {
        when (event) {
            is CodingEvent.Failed -> { failure = event; return null }
            is CodingEvent.FinalText -> { answerSeen = true; failure = null }
            is CodingEvent.ToolStarted -> failure = null
            is CodingEvent.OutputTruncated -> failure = null
            else -> Unit
        }
        return event
    }
}
