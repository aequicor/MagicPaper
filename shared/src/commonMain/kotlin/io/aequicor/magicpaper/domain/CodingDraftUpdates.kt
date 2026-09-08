package io.aequicor.magicpaper.domain

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Apply every event, but allocate/publish at most one text snapshot per display interval. */
internal fun CodingRunRecorder.recordDrafts(
    events: Flow<CodingEvent>,
    intervalMillis: Long = 50,
    onEvent: suspend (CodingEvent) -> Unit = {},
): Flow<CodingDraft> = channelFlow {
    val lock = Mutex()
    var dirty = false
    val publisher = launch {
        while (isActive) {
            delay(intervalMillis)
            lock.withLock {
                if (dirty) { send(draft(active = true)); dirty = false }
            }
        }
    }
    try {
        events.collect { event ->
            onEvent(event)
            lock.withLock {
                apply(event)
                dirty = true
                // Phase changes, failures and completion should not wait for a timer.
                if (event !is CodingEvent.TextDelta && event !is CodingEvent.ThinkingDelta && event !is CodingEvent.ToolProgress) {
                    send(draft(active = true))
                    dirty = false
                }
            }
        }
        lock.withLock { if (dirty) send(draft(active = true)) }
    } finally {
        publisher.cancelAndJoin()
    }
    // Only complete UI snapshots may be conflated. Raw deltas are never dropped.
}.buffer(Channel.CONFLATED)
