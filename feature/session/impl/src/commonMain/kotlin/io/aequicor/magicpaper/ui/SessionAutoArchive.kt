package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

internal const val CODING_ARCHIVE_DELAY = 2 * 60 * 60 * 1000L
internal const val CHAT_ARCHIVE_DELAY = 2 * 24 * 60 * 60 * 1000L

/** Application lifetime timer, independent of screen visibility and UI dispatch. */
internal fun sessionArchiveTicks() = flow {
    while (true) {
        delay(60_000)
        emit(Unit)
    }
}.flowOn(Dispatchers.Default)

internal val CodingSessionUi.readyForArchive: Boolean get() =
    !session.archived && status == CodingSessionStatus.IDLE && canChangeHistory &&
        session.pendingRun == null && !interruptedRequest && !failedRequest &&
        session.sessionKind != SessionKind.IMMUNITY

internal fun CodingUi.readyForArchive(item: CodingSessionUi): Boolean {
    if (!item.readyForArchive) return false
    val organism = organisms.values.firstOrNull { it.zygoteId == item.session.id } ?: return true
    // The sidebar presents the root with its children's aggregate status.
    return sessions.none { child -> child.session.id != item.session.id &&
        (child.session.organismId == organism.id || child.session.id in organism.sessions) &&
        !child.session.archived && child.session.sessionKind != SessionKind.IMMUNITY && !child.readyForArchive }
}

internal fun ChatSession.archiveDue(now: Long): Boolean = !archived && pendingRun == null &&
    queuedPrompts.isEmpty() && now - maxOf(updatedAt, archiveRestoredAt ?: updatedAt) >= CHAT_ARCHIVE_DELAY
