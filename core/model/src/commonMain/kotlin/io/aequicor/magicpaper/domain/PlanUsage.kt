package io.aequicor.magicpaper.domain

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt

/**
 * One rolling allowance of a subscription plan as the provider reported it. [scope] names the model family or
 * metered product the window is restricted to; null covers every model of the plan.
 */
data class PlanUsageWindow(
    val id: String,
    val usedFraction: Float,
    val durationMinutes: Long? = null,
    val resetsAtEpochSeconds: Long? = null,
    val scope: String? = null,
)

/**
 * The latest allowance of one subscription provider. It is never persisted: the figures age within hours and the
 * provider repeats them with the next request. [stale] marks figures a later refresh failed to confirm.
 */
data class PlanUsage(
    val provider: ProviderType,
    val windows: List<PlanUsageWindow> = emptyList(),
    val plan: String? = null,
    /** The provider refuses ordinary requests until a window resets. */
    val limited: Boolean = false,
    val observedAt: Long = 0,
    val stale: Boolean = false,
) {
    /** Shortest window first; a plan-wide window precedes the model-scoped ones of the same length. */
    val orderedWindows: List<PlanUsageWindow> get() = windows.sortedWith(WINDOW_ORDER)

    /** A rolling update may carry only some windows or omit the plan; it never clears a value observed before. */
    fun merge(update: PlanUsage): PlanUsage {
        val updated = update.windows.mapTo(mutableSetOf()) { it.id }
        return update.copy(windows = windows.filter { it.id !in updated } + update.windows, plan = update.plan ?: plan)
    }

    private companion object {
        val WINDOW_ORDER = compareBy<PlanUsageWindow>({ it.durationMinutes ?: Long.MAX_VALUE }, { it.scope != null }, { it.scope })
    }
}

/** "Лимиты Claude · Max": the provider the allowance belongs to and, when reported, its plan. */
fun PlanUsage.title(): String = buildString {
    append(if (provider == ProviderType.ANTHROPIC_SUBSCRIPTION) "Лимиты Claude" else "Лимиты ChatGPT")
    plan?.takeIf { it.isNotBlank() && it != "unknown" }?.let { name ->
        append(" · ").append(name.split('_').joinToString(" ") { part -> part.replaceFirstChar(Char::uppercaseChar) })
    }
}

/** "Неделя · Opus"; a plan-wide window says so only beside a model-scoped one of the same length. */
fun PlanUsage.windowTitle(window: PlanUsageWindow): String {
    val span = window.durationMinutes?.let { minutes ->
        when {
            minutes == WEEK -> "Неделя"
            minutes % WEEK == 0L -> "${minutes / WEEK} нед."
            minutes == DAY -> "Сутки"
            minutes % DAY == 0L -> "${minutes / DAY} дн."
            minutes % 60 == 0L -> (minutes / 60).let { "$it ${hours(it)}" }
            else -> "$minutes мин"
        }
    } ?: when (window.id.substringAfterLast(':')) {
        "primary" -> "Основной лимит"
        "secondary" -> "Дополнительный лимит"
        else -> "Лимит"
    }
    val scope = window.scope
        ?: "все модели".takeIf { windows.any { it.scope != null && it.durationMinutes == window.durationMinutes } }
    return scope?.let { "$span · $it" } ?: span
}

fun PlanUsageWindow.percent(): String = "${(usedFraction * 100).roundToInt()}%"

/** A countdown within a day, the weekday and local time beyond it. */
fun PlanUsageWindow.resetText(nowMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String? {
    val resetsAt = resetsAtEpochSeconds ?: return null
    val minutes = ((resetsAt - nowMillis / 1000).coerceAtLeast(0) + 59) / 60
    return when {
        minutes == 0L -> "Сброс ожидается"
        minutes < 60 -> "Сброс через $minutes мин"
        minutes < DAY -> "Сброс через ${minutes / 60} ч" + (minutes % 60).takeIf { it > 0 }?.let { " $it мин" }.orEmpty()
        else -> {
            val local = kotlin.time.Instant.fromEpochSeconds(resetsAt).toLocalDateTime(zone)
            "Сброс в ${WEEKDAYS[local.dayOfWeek.ordinal]}, ${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
        }
    }
}

private fun hours(count: Long) = when {
    count % 100 in 11..14 -> "часов"
    count % 10 == 1L -> "час"
    count % 10 in 2..4 -> "часа"
    else -> "часов"
}

private const val DAY = 24 * 60L
private const val WEEK = 7 * DAY
private val WEEKDAYS = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
