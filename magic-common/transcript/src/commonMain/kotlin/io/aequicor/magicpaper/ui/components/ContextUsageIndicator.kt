package io.aequicor.magicpaper.ui.components

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

/** "584,8 тыс.": the magnitude the reader compares, not every digit. */
internal fun compactTokens(value: Long): String {
    fun scaled(unit: Long, suffix: String): String {
        val tenths = (value * 10.0 / unit).roundToLong()
        return (if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10},${tenths % 10}") + " $suffix"
    }
    return when {
        value < 1_000 -> value.toString()
        value < 999_950 -> scaled(1_000, "тыс.")
        else -> scaled(1_000_000, "млн")
    }
}

/** "≈584,8 тыс. / 1 млн": what the share is taken of; null when the engine reported neither figure. */
internal fun contextUsageFigures(snapshot: ContextUsageSnapshot?): String? {
    val used = snapshot?.used?.let { (if (snapshot.approximate) "≈" else "") + compactTokens(it) }
    val limit = snapshot?.limit?.let(::compactTokens)
    return if (used == null && limit == null) null else "${used ?: "—"}" + limit?.let { " / $it" }.orEmpty()
}

internal fun contextUsageShare(snapshot: ContextUsageSnapshot?): String = snapshot?.fraction?.let { "${(it * 100).roundToLong()}%" }
    ?: if (snapshot?.used == null && snapshot?.limit == null) "Нет данных" else "—"

/**
 * The composer's context meter. Its details show the window and, for a subscription connection, the plan's
 * allowances; opening them asks [onOpen] to refresh those that can be queried.
 */
@Composable
fun ContextUsageIndicator(
    snapshot: ContextUsageSnapshot?,
    model: String = snapshot?.model.orEmpty(),
    compacting: Boolean = false,
    plan: PlanUsage? = null,
    onOpen: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val fraction = snapshot?.fraction
    val label = fraction?.let { (if (snapshot.approximate) "≈" else "") + "${(it * 100).roundToLong()}%" } ?: "—"
    Box {
        PaperContextIndicator(fraction, label, {
            expanded = !expanded
            if (expanded) onOpen()
        }, compacting = compacting)
        PaperMenuHost(expanded, { expanded = false }, Modifier.width(340.dp)) {
            ContextUsageDetails(snapshot, model, compacting, plan)
        }
    }
}

@Composable
fun ContextUsageDetails(
    snapshot: ContextUsageSnapshot?,
    model: String,
    compacting: Boolean,
    plan: PlanUsage?,
    modifier: Modifier = Modifier,
    now: Long = rememberMinuteClock(),
) {
    val colors = LocalPaperColors.current
    Column(modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PaperUsageRow("Контекстное окно", contextUsageShare(snapshot), snapshot?.fraction,
                detail = contextUsageFigures(snapshot), heading = true)
            val status = when {
                compacting -> "Сжатие контекста…"
                snapshot?.approximate == true -> "Приблизительная оценка движка"
                snapshot?.fraction == null -> "Ожидание данных движка"
                else -> null
            }
            listOfNotNull(model.takeIf { it.isNotBlank() }, status).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                PaperText(it, role = PaperTextRole.LABEL, color = colors.secondaryText, maxLines = 2)
            }
        }
        plan?.let { usage ->
            PaperDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperText(usage.title(), Modifier.weight(1f), color = colors.secondaryText, maxLines = 2)
                if (usage.limited) PaperText("Лимит исчерпан", role = PaperTextRole.LABEL, color = colors.error)
            }
            usage.orderedWindows.forEach { window ->
                PaperUsageRow(usage.windowTitle(window), window.percent(), window.usedFraction, detail = window.resetText(now))
            }
            when {
                usage.windows.isEmpty() && usage.stale -> "Лимиты сейчас недоступны"
                usage.windows.isEmpty() -> "Получаем данные о лимитах…"
                usage.stale -> "Не удалось обновить, показаны прежние данные"
                else -> null
            }?.let { PaperText(it, role = PaperTextRole.LABEL, color = colors.secondaryText) }
        }
    }
}

/** Reset countdowns stay current while the details are open; they are composed only then. */
@Composable
private fun rememberMinuteClock(): Long = produceState(Id.now()) {
    while (true) {
        delay(30_000)
        value = Id.now()
    }
}.value
