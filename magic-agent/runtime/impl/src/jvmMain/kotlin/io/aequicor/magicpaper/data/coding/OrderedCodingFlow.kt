package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.CodingEvent
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** flowOn cancels its buffer on upstream failure. Deliver emitted output before an operational failure. */
internal fun Flow<CodingEvent>.flowOnPreservingOutput(context: CoroutineContext): Flow<CodingEvent> =
    map<CodingEvent, CodingDelivery> { CodingDelivery.Event(it) }
        .catch { failure ->
            // User cancellation remains prompt and must not become a queued operation result.
            if (failure is CancellationException) throw failure
            emit(CodingDelivery.Failure(failure))
        }
        .flowOn(context)
        .map { delivery -> when (delivery) {
            is CodingDelivery.Event -> delivery.event
            is CodingDelivery.Failure -> throw delivery.cause
        } }

private sealed interface CodingDelivery {
    data class Event(val event: CodingEvent) : CodingDelivery
    data class Failure(val cause: Throwable) : CodingDelivery
}
