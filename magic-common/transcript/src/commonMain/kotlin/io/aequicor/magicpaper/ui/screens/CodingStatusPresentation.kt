package io.aequicor.magicpaper.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.CodingSessionStatus

val CodingSessionStatus.label: String
    get() = when (this) {
        CodingSessionStatus.WORKING -> "работает"
        CodingSessionStatus.WAITING -> "Ждём вашего ответа"
        CodingSessionStatus.CONFIRMATION -> "ждёт подтверждения доработки"
        CodingSessionStatus.BLOCKED -> "выполнение остановлено"
        CodingSessionStatus.QUEUED -> "ждёт родителя"
        CodingSessionStatus.SCHEDULED -> "ждёт события или времени"
        CodingSessionStatus.IDLE -> "ждёт запроса"
        CodingSessionStatus.UNREAD -> "Работа завершена · результат не прочитан"
        CodingSessionStatus.NEEDS_TESTING -> "Работа завершена · нужна ручная проверка"
    }

public val CodingSessionStatus.activityTone: PaperActivityTone
    get() = when (this) {
        CodingSessionStatus.IDLE -> PaperActivityTone.READY
        CodingSessionStatus.UNREAD -> PaperActivityTone.UNREAD
        CodingSessionStatus.NEEDS_TESTING -> PaperActivityTone.NEEDS_TESTING
        CodingSessionStatus.WORKING -> PaperActivityTone.WORKING
        CodingSessionStatus.BLOCKED, CodingSessionStatus.WAITING, CodingSessionStatus.CONFIRMATION -> PaperActivityTone.ATTENTION
        CodingSessionStatus.QUEUED, CodingSessionStatus.SCHEDULED -> PaperActivityTone.QUEUED
    }

/**
 * One dot for the whole workspace: the most demanding session wins, so a single run in progress
 * outranks a question, a question outranks unread results, and only a quiet workspace is green.
 * Expressed over the per-session tones above, so a status never means two different colours.
 */
public fun aggregateDockTone(tones: Collection<PaperActivityTone>): PaperActivityTone = when {
    PaperActivityTone.WORKING in tones -> PaperActivityTone.WORKING
    PaperActivityTone.ATTENTION in tones -> PaperActivityTone.ATTENTION
    PaperActivityTone.UNREAD in tones -> PaperActivityTone.UNREAD
    else -> PaperActivityTone.READY
}

@Composable
fun ActivityDot(status: CodingSessionStatus, modifier: Modifier = Modifier, size: Int = 10) {
    PaperActivityIndicator(tone = status.activityTone, label = status.label,
        running = status == CodingSessionStatus.WORKING, modifier = modifier, size = size.dp)
}

@Composable
fun ImmunityDiamondButton(status: CodingSessionStatus, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PaperActivityIndicatorButton(tone = status.activityTone, label = "Иммунитет: ${status.label}",
        onClick = onClick, modifier = modifier, selected = selected,
        running = status == CodingSessionStatus.WORKING, size = if (selected) 12.dp else 10.dp)
}
