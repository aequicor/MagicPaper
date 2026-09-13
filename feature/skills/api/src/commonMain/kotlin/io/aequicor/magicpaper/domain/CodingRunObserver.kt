package io.aequicor.magicpaper.domain

import kotlinx.coroutines.flow.Flow

/** Optional skill learning decorates a complete run without controlling the engine. */
fun interface CodingRunObserver {
    fun observe(runId: String, events: Flow<CodingEvent>, cancelled: () -> Boolean): Flow<CodingEvent>
}
