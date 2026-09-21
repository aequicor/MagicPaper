package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext





fun Flow<CodingEvent>.withTools(session: ToolSession): Flow<CodingEvent> = channelFlow {
    val tracker = NativeToolEvents(session)
    val close = session.events.observe { send(it.codingEvent()) }
    try { this@withTools.flowOn(session).collect { event ->
        val rawName = when (event) {
            is CodingEvent.ToolStarted -> event.tool; is CodingEvent.ToolFinished -> event.tool; is CodingEvent.ToolProgress -> event.tool; else -> ""
        }
        val owned = session.definitions.any { rawName == it.wireName || rawName == "magicpaper_agent_tools:${it.wireName}" }
        if (!owned) {
            if (!tracker.publishNativeEvent(event)) {
                if (event == CodingEvent.Finished) tracker.finishNativeEvents()
                send(event)
            }
        }
    } } finally {
        withContext(NonCancellable) { tracker.finishNativeEvents() }
        close()
    }
}

private class NativeToolEvents(val session: ToolSession) {
    val context get() = session.context
    val nativeCalls = mutableMapOf<String, Pair<String, ToolCategory>>()
    val pendingNativeEvents = mutableMapOf<String, ToolEvent>()
    val nativeEventLock = Mutex()
}

private val nativeTerminalPhases = setOf(ToolPhase.SUCCEEDED, ToolPhase.FAILED, ToolPhase.CANCELLED)

private suspend fun NativeToolEvents.publishNativeEvent(event: CodingEvent): Boolean = nativeEventLock.withLock {
    val native = nativeEvent(event) ?: return@withLock false
    if (session.recordNative(native)) {
        if (native.callId.isNotBlank()) {
            if (native.phase in setOf(ToolPhase.STARTED, ToolPhase.PROGRESS, ToolPhase.WAITING))
                pendingNativeEvents[native.callId] = native
            else pendingNativeEvents.remove(native.callId)
        }
        session.publishNative(native)
    } else pendingNativeEvents.remove(native.callId)
    true
}

private suspend fun NativeToolEvents.finishNativeEvents() = nativeEventLock.withLock {
    pendingNativeEvents.values.toList().forEach { pending ->
        val uncertain = pending.copy(phase = ToolPhase.UNKNOWN, result = "Выполнение завершилось без подтверждённого результата инструмента")
        // A persistence failure remains uncertain, but a known terminal receipt wins over
        // this fallback even when completion arrived through a separate reconciliation path.
        val accepted = runCatching { session.recordNative(uncertain) }.getOrDefault(true)
        if (accepted) runCatching { session.publishNative(uncertain) }
    }
    pendingNativeEvents.clear()
}


private fun NativeToolEvents.nativeEvent(event: CodingEvent): ToolEvent? {
    val name: String; val call: String; val phase: ToolPhase; val summary: String; val result: String
    var exec = false
    when (event) {
        is CodingEvent.ToolStarted -> { name = event.tool; call = event.callId; phase = ToolPhase.STARTED; summary = event.summary; result = ""; exec = event.isExec }
        is CodingEvent.ToolProgress -> { name = event.tool; call = event.callId; phase = event.phase ?: ToolPhase.PROGRESS; summary = ""; result = event.resultPreview }
        is CodingEvent.ToolFinished -> { name = event.tool; call = event.callId; phase = event.phase ?: if (event.isError) ToolPhase.FAILED else ToolPhase.SUCCEEDED; summary = ""; result = event.resultPreview }
        else -> return null
    }
    val mappedId = ToolCatalog.nativeId(name, exec)
    val mappedCategory = (event as? CodingEvent.ToolStarted)?.category
        ?: ToolCatalog.definitions.firstOrNull { it.id == mappedId }?.category ?: ToolCategory.ACTION
    val (id, category) = if (call.isBlank()) mappedId to mappedCategory
        else nativeCalls.getOrPut(call) { mappedId to mappedCategory }
    val identity = if (call.isBlank()) "" else "${context.projectId}/${context.ownerSessionId}/${context.requestId}/native/${call.replace("%", "%25").replace("/", "%2F")}"
    val title = when (event) {
        is CodingEvent.ToolStarted -> event.title
        is CodingEvent.ToolFinished -> event.title
        else -> null
    }
    return ToolEvent(context.projectId, context.ownerSessionId, context.requestId, identity, id, category, phase, summary, result, title,
        sources = (event as? CodingEvent.ToolFinished)?.sources.orEmpty())
}

