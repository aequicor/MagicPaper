package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

internal const val CHAT_ARCHIVE_DELAY = 2 * 24 * 60 * 60 * 1000L

/** Application lifetime timer, independent of screen visibility and UI dispatch. */
fun sessionArchiveTicks() = flow {
    while (true) {
        delay(60_000)
        emit(Unit)
    }
}.flowOn(Dispatchers.Default)

fun ChatSession.archiveDue(now: Long): Boolean = !archived && pendingRun == null &&
    queuedPrompts.isEmpty() && now - maxOf(updatedAt, archiveRestoredAt ?: updatedAt) >= CHAT_ARCHIVE_DELAY
