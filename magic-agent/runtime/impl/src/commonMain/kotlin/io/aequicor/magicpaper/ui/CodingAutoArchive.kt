package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*

const val CODING_ARCHIVE_DELAY = 2 * 60 * 60 * 1000L

val CodingSessionUi.readyForArchive: Boolean get() =
    !session.archived && status == CodingSessionStatus.IDLE && canChangeHistory &&
        session.pendingRun == null && !interruptedRequest && !failedRequest &&
        session.sessionKind != SessionKind.IMMUNITY

fun CodingUi.readyForArchive(item: CodingSessionUi): Boolean {
    if (!item.readyForArchive) return false
    val organism = organisms.values.firstOrNull { it.zygoteId == item.session.id } ?: return true
    // The sidebar presents the root with its children's aggregate status.
    return sessions.none { child -> child.session.id != item.session.id &&
        (child.session.organismId == organism.id || child.session.id in organism.sessions) &&
        !child.session.archived && child.session.sessionKind != SessionKind.IMMUNITY && !child.readyForArchive }
}

