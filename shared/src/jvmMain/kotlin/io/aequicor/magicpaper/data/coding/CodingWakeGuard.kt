package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.CodingEvent
import io.aequicor.magicpaper.domain.tools.ToolPhase
import io.aequicor.magicpaper.domain.tools.ToolSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

/** A wall-clock gap detects suspension even on platforms whose monotonic clock stops in sleep. */
internal fun Flow<CodingEvent>.withWakeGuard(
    clock: () -> Long = System::currentTimeMillis,
    awaitingUser: () -> Boolean = { false },
    onStalled: suspend () -> Unit = {},
): Flow<CodingEvent> = channelFlow {
    val activity = AtomicLong(clock())
    val waiting = ConcurrentHashMap.newKeySet<String>()
    val stopObserving = coroutineContext[ToolSession]?.events?.observe { event ->
        activity.set(clock())
        if (event.phase == ToolPhase.WAITING) waiting.add(event.callId)
        else waiting.remove(event.callId)
    }
    val watcher = launch {
        var previous = clock()
        var resumedAt: Long? = null
        while (true) {
            delay(1_000)
            val now = clock()
            if (now - previous > 30_000) resumedAt = now
            previous = now
            val resume = resumedAt ?: continue
            if (activity.get() >= resume || awaitingUser() || waiting.isNotEmpty()) {
                resumedAt = null
            } else if (now - resume >= 30_000) {
                // Pi can be blocked in a pipe read; interrupt the process before cancelling collection.
                onStalled()
                throw CodingWakeException()
            }
        }
    }
    try {
        collect {
            activity.set(clock())
            send(it)
        }
    } finally {
        stopObserving?.invoke()
        watcher.cancel()
    }
}.catch { error ->
    if (error !is CodingWakeException) throw error
    emit(CodingEvent.Failed("Агент не возобновил ответ после приостановки компьютера. Запустите запрос повторно."))
    emit(CodingEvent.Finished)
}

private class CodingWakeException : IllegalStateException("Agent stream did not resume after system suspension")
