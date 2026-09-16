package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aequicor.magicpaper.ui.components.paperChatDisclosure

public enum class PaperResearchStepState { COMPLETE, ACTIVE, PAUSED, FAILED }

/** Research mockup: a quiet disclosure, connected operations and a small text action. */
@Composable
public fun PaperResearchActivityPanel(title: String, state: PaperResearchStepState,
    expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalPaperColors.current
    val ink = if (state == PaperResearchStepState.FAILED) colors.error else colors.action
    Column(modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).semantics {
            contentDescription = if (expanded) "Свернуть ход исследования" else "Развернуть ход исследования"
            stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто"
        }.paperChatDisclosure { onExpandedChange(!expanded) },
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(16.dp)) {
                val stroke = 1.4.dp.toPx()
                fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(ink,
                    Offset(size.width * x1, size.height * y1), Offset(size.width * x2, size.height * y2), stroke, StrokeCap.Round)
                when (state) {
                    PaperResearchStepState.ACTIVE -> {
                        val sparkle = Path().apply {
                            moveTo(size.width * .4f, size.height * .1f)
                            lineTo(size.width * .52f, size.height * .4f)
                            lineTo(size.width * .82f, size.height * .52f)
                            lineTo(size.width * .52f, size.height * .64f)
                            lineTo(size.width * .4f, size.height * .94f)
                            lineTo(size.width * .28f, size.height * .64f)
                            lineTo(size.width * .04f, size.height * .52f)
                            lineTo(size.width * .28f, size.height * .4f)
                            close()
                        }
                        drawPath(sparkle, ink, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                        line(.83f, .04f, .83f, .27f); line(.71f, .15f, .95f, .15f)
                    }
                    PaperResearchStepState.PAUSED -> { line(.35f, .2f, .35f, .8f); line(.65f, .2f, .65f, .8f) }
                    else -> {
                        drawCircle(ink, size.width * .4f, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                        if (state == PaperResearchStepState.COMPLETE) {
                            line(.27f, .5f, .43f, .66f); line(.43f, .66f, .74f, .34f)
                        } else { line(.5f, .25f, .5f, .55f); drawCircle(ink, stroke * .6f, Offset(size.width * .5f, size.height * .74f)) }
                    }
                }
            }
            PaperText(title, Modifier.weight(1f), style = LocalPaperTypography.current.chrome.copy(
                fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium), color = ink)
            Canvas(Modifier.size(16.dp)) {
                val path = Path().apply {
                    if (expanded) { moveTo(size.width * .25f, size.height * .38f); lineTo(size.width * .5f, size.height * .63f); lineTo(size.width * .75f, size.height * .38f) }
                    else { moveTo(size.width * .38f, size.height * .25f); lineTo(size.width * .63f, size.height * .5f); lineTo(size.width * .38f, size.height * .75f) }
                }
                drawPath(path, ink, style = androidx.compose.ui.graphics.drawscope.Stroke(1.4.dp.toPx()))
            }
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), content = content)
            if (onAction != null && actionLabel != null) Row(Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End) {
                PaperAction(onAction, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    PaperText(actionLabel, style = LocalPaperTypography.current.chrome.copy(
                        fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal), color = colors.action)
                }
            }
        }
    }
}

/** Compact operation log: the connector belongs to the marker lane, not the text. */
@Composable
public fun PaperResearchActivityStep(title: String, detail: String?, state: PaperResearchStepState,
    last: Boolean, modifier: Modifier = Modifier, contentLabel: String? = null,
    detailProblem: String? = null,
    content: @Composable ColumnScope.() -> Unit = {}) {
    val colors = LocalPaperColors.current
    val tone = when (state) {
        PaperResearchStepState.COMPLETE -> colors.success
        PaperResearchStepState.ACTIVE -> colors.action
        PaperResearchStepState.PAUSED -> colors.secondaryText
        PaperResearchStepState.FAILED -> colors.error
    }
    val status = when (state) {
        PaperResearchStepState.COMPLETE -> "Завершено"
        PaperResearchStepState.ACTIVE -> "Выполняется"
        PaperResearchStepState.PAUSED -> "Приостановлено"
        PaperResearchStepState.FAILED -> "Ошибка"
    }
    Row(modifier.fillMaxWidth().semantics { stateDescription = status }.drawBehind {
        if (!last) drawLine(colors.border, Offset(8.dp.toPx(), 18.dp.toPx()),
            Offset(8.dp.toPx(), size.height + 2.dp.toPx()), 1.dp.toPx())
    }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.padding(top = 2.dp).size(16.dp)) {
            val stroke = 1.4.dp.toPx()
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tone,
                Offset(size.width * x1, size.height * y1), Offset(size.width * x2, size.height * y2), stroke, StrokeCap.Round)
            when (state) {
                PaperResearchStepState.COMPLETE -> { line(.23f, .48f, .43f, .7f); line(.43f, .7f, .78f, .27f) }
                PaperResearchStepState.ACTIVE -> {
                    drawCircle(tone, radius = size.width * .36f, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    drawCircle(tone, radius = size.width * .12f)
                }
                PaperResearchStepState.PAUSED -> { line(.37f, .25f, .37f, .75f); line(.63f, .25f, .63f, .75f) }
                PaperResearchStepState.FAILED -> {
                    line(.5f, .2f, .5f, .55f)
                    drawCircle(tone, stroke * .65f, Offset(size.width * .5f, size.height * .8f))
                }
            }
        }
        Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 18.dp)) {
            PaperText(title, style = LocalPaperTypography.current.chrome.copy(
                fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium))
            if (!detail.isNullOrBlank() || !detailProblem.isNullOrBlank()) PaperText(buildAnnotatedString {
                if (!detail.isNullOrBlank()) append(detail)
                if (!detailProblem.isNullOrBlank()) {
                    if (length > 0) append("; ")
                    withStyle(SpanStyle(color = colors.error)) { append(detailProblem) }
                }
            }, Modifier.padding(top = 4.dp),
                style = LocalPaperTypography.current.chrome.copy(fontSize = 11.sp, lineHeight = 16.sp,
                    fontWeight = FontWeight.Normal), color = colors.secondaryText)
            if (contentLabel != null) PaperText(contentLabel, Modifier.padding(top = 8.dp),
                style = LocalPaperTypography.current.chrome.copy(fontSize = 11.sp, lineHeight = 16.sp,
                    fontWeight = FontWeight.Normal), color = colors.secondaryText)
            content()
        }
    }
}

/** A citation is a small numbered reference, never body-sized prose or a result preview. */
@Composable
public fun PaperResearchSourceLink(title: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    number: Int? = null) {
    val colors = LocalPaperColors.current
    Row(modifier.fillMaxWidth().heightIn(min = 28.dp)
        .paperClickable(role = Role.Button, onClickLabel = "Открыть источник", onClick = onClick)
        .padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        if (number != null) Box(Modifier.sizeIn(minWidth = 22.dp, minHeight = 22.dp)
            .background(colors.successSurface, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp), Alignment.Center) {
            PaperText(number.toString(), style = LocalPaperTypography.current.chrome.copy(fontSize = 11.sp, lineHeight = 16.sp))
        } else Canvas(Modifier.size(13.dp)) {
            val stroke = 1.dp.toPx()
            drawRect(colors.secondaryText, topLeft = Offset(size.width * .2f, 0f),
                size = androidx.compose.ui.geometry.Size(size.width * .65f, size.height),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawLine(colors.secondaryText, Offset(size.width * .35f, size.height * .4f), Offset(size.width * .7f, size.height * .4f), stroke)
            drawLine(colors.secondaryText, Offset(size.width * .35f, size.height * .65f), Offset(size.width * .7f, size.height * .65f), stroke)
        }
        PaperText(title, Modifier.weight(1f), style = LocalPaperTypography.current.chrome.copy(
            fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
            color = colors.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Diagonal corner arrows remain legible independent of the platform's symbol font. */
@Composable
public fun PaperComposerExpandButton(expanded: Boolean, onClick: () -> Unit) {
    val label = if (expanded) "Свернуть поле ввода" else "Развернуть поле ввода"
    val ink = LocalPaperColors.current.secondaryText
    PaperTooltip(label) {
        PaperIconButton(label, onClick, Modifier.semantics {
            stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто"
        }) {
            Canvas(Modifier.size(16.dp)) {
                fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(ink,
                    Offset(size.width * x1, size.height * y1), Offset(size.width * x2, size.height * y2),
                    1.4.dp.toPx(), StrokeCap.Round)
                for (flip in listOf(false, true)) {
                    fun arrow(x1: Float, y1: Float, x2: Float, y2: Float) {
                        if (flip) line(1-x1, 1-y1, 1-x2, 1-y2) else line(x1, y1, x2, y2)
                    }
                    arrow(.58f, .42f, .88f, .12f)
                    if (expanded) { arrow(.58f, .12f, .58f, .42f); arrow(.58f, .42f, .88f, .42f) }
                    else { arrow(.58f, .12f, .88f, .12f); arrow(.88f, .12f, .88f, .42f) }
                }
            }
        }
    }
}

@Preview(name = "Search and reading", group = "Research activity", widthDp = 440, heightDp = 360)
@Preview(name = "Narrow large text", group = "Research activity", widthDp = 320, heightDp = 900, fontScale = 2f)
@Composable
internal fun PaperResearchActivityPreview() = PaperTheme {
    PaperSurface {
        Column(Modifier.padding(16.dp)) {
            PaperResearchActivityPanel("Изучаю найденные источники", PaperResearchStepState.ACTIVE, true, {},
                actionLabel = "Остановить", onAction = {}) {
                PaperResearchActivityStep("Проверка выбранных источников", "Прочитано источников: 4", PaperResearchStepState.COMPLETE, false,
                    detailProblem = "недоступно: 2")
                PaperResearchActivityStep("Поиск дополнительных материалов", "Найдено источников: 2", PaperResearchStepState.COMPLETE, false,
                    contentLabel = "Доступные источники") {
                    PaperResearchSourceLink("Kotlin Documentation", {})
                    PaperResearchSourceLink("Jetpack Compose — руководство и примеры", {})
                }
                PaperResearchActivityStep("Чтение материалов", "Выполняется", PaperResearchStepState.ACTIVE, true)
            }
        }
    }
}

@Preview(name = "Paused and failed", group = "Research activity", widthDp = 440, heightDp = 300)
@Composable
internal fun PaperResearchActivityStatesPreview() = PaperTheme {
    PaperSurface {
        Column(Modifier.padding(16.dp)) {
            PaperResearchActivityPanel("Исследование приостановлено", PaperResearchStepState.PAUSED, true, {},
                actionLabel = "Продолжить", onAction = {}) {
                PaperResearchActivityStep("Поиск дополнительных материалов", "Приостановлено", PaperResearchStepState.PAUSED, true)
            }
            PaperResearchActivityPanel("Исследование прервано", PaperResearchStepState.FAILED, true, {}) {
                PaperResearchActivityStep("Открытие источника", "Не удалось открыть. Повторите попытку.", PaperResearchStepState.FAILED, true)
            }
        }
    }
}
