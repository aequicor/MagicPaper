package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.magicpaper.designsystem.LocalPaperColors
import io.aequicor.magicpaper.designsystem.LocalPaperSpacing
import io.aequicor.magicpaper.designsystem.PaperAction
import io.aequicor.magicpaper.designsystem.PaperField
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperTextRole
import io.aequicor.magicpaper.domain.OrganismLimits
import androidx.compose.ui.unit.dp

internal enum class AgentLimitField(val label: String, val minimum: Long = 1, val maximum: Long = Int.MAX_VALUE.toLong()) {
    TOKENS("Бюджет задачи, токенов", maximum = Long.MAX_VALUE),
    DURATION("Время работы задачи, минут", maximum = Long.MAX_VALUE),
    ACTIVE_SESSIONS("Одновременных сессий в задаче"),
    DEPTH("Глубина дочерних сессий"),
    RETRIES("Повторов после ошибки", minimum = 0),
    QUEUE_SIZE("Запросов в очереди"),
    CONTEXT_CHARACTERS("Передаваемый контекст, символов"),
}

/** Keeps invalid, unsaved input local instead of quietly saving an earlier value. */
internal data class AgentLimitsDraft(val values: Map<AgentLimitField, String> = emptyMap()) {
    operator fun get(field: AgentLimitField): String = values[field].orEmpty()
    fun edited(field: AgentLimitField, value: String) = copy(values = values + (field to value))
    fun error(field: AgentLimitField): String? {
        val text = get(field).trim()
        if (text.isEmpty()) return null
        if (field == AgentLimitField.DURATION) return when {
            parseMinutes(text) == null -> "Введите положительное число минут"
            else -> null
        }
        val value = text.toLongOrNull()
        return when {
            value == null -> "Введите целое число"
            value < field.minimum -> if (field.minimum == 0L) "Введите 0 или больше" else "Введите число больше 0"
            value > field.maximum -> "Максимум: ${field.maximum}"
            else -> null
        }
    }
    val valid: Boolean get() = AgentLimitField.entries.none { error(it) != null }
    fun limits(): OrganismLimits? {
        if (!valid) return null
        fun number(field: AgentLimitField) = get(field).trim().toLongOrNull()
        return OrganismLimits(
            tokens = number(AgentLimitField.TOKENS),
            durationMillis = parseMinutes(get(AgentLimitField.DURATION).trim()),
            activeSessions = number(AgentLimitField.ACTIVE_SESSIONS)?.toInt(),
            depth = number(AgentLimitField.DEPTH)?.toInt(),
            retries = number(AgentLimitField.RETRIES)?.toInt(),
            queueSize = number(AgentLimitField.QUEUE_SIZE)?.toInt(),
            contextCharacters = number(AgentLimitField.CONTEXT_CHARACTERS)?.toInt(),
        )
    }

    companion object {
        fun from(limits: OrganismLimits) = AgentLimitsDraft(mapOf(
            AgentLimitField.TOKENS to limits.tokens?.toString().orEmpty(),
            AgentLimitField.DURATION to limits.durationMillis?.let(::formatMinutes).orEmpty(),
            AgentLimitField.ACTIVE_SESSIONS to limits.activeSessions?.toString().orEmpty(),
            AgentLimitField.DEPTH to limits.depth?.toString().orEmpty(),
            AgentLimitField.RETRIES to limits.retries?.toString().orEmpty(),
            AgentLimitField.QUEUE_SIZE to limits.queueSize?.toString().orEmpty(),
            AgentLimitField.CONTEXT_CHARACTERS to limits.contextCharacters?.toString().orEmpty(),
        ))
    }
}

/** Decimal minutes are converted with integer arithmetic, including imported millisecond values. */
private fun parseMinutes(text: String): Long? {
    val parts = text.replace(',', '.').split('.')
    if (parts.size !in 1..2 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
    val whole = parts[0].toLongOrNull() ?: return null
    if (whole > Long.MAX_VALUE / 60_000) return null
    val fraction = parts.getOrNull(1).orEmpty()
    if (fraction.length > 6) return null
    val fractionMillis = (fraction.padEnd(6, '0').toLong() * 60_000 + 500_000) / 1_000_000
    val wholeMillis = whole * 60_000
    if (fractionMillis > Long.MAX_VALUE - wholeMillis) return null
    return (wholeMillis + fractionMillis).takeIf { it > 0 }
}

private fun formatMinutes(millis: Long): String {
    val whole = millis / 60_000
    val fraction = ((millis % 60_000 * 1_000_000 + 30_000) / 60_000)
        .toString().padStart(6, '0').trimEnd('0')
    return if (fraction.isEmpty()) whole.toString() else "$whole.$fraction"
}

@Composable
internal fun AgentLimitsSettingsSection(draft: AgentLimitsDraft, onChange: (AgentLimitsDraft) -> Unit) {
    val spacing = LocalPaperSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        PaperText("Ограничения агентов", role = PaperTextRole.TITLE, color = LocalPaperColors.current.action)
        PaperText(
            "Пустое поле — без ограничения. Настройки действуют для всех задач.",
            role = PaperTextRole.LABEL, color = LocalPaperColors.current.secondaryText,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            for (field in AgentLimitField.entries) PaperField(
                value = draft[field], onValueChange = { onChange(draft.edited(field, it)) },
                label = field.label, errorMessage = draft.error(field),
                supportingText = if (draft[field].isBlank()) "Без ограничения" else null,
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().testTag("agent-limit.${field.name}"),
            )
        }
        PaperAction(onClick = { onChange(AgentLimitsDraft()) }, enabled = draft.values.values.any { it.isNotBlank() }) {
            PaperText("Снять ограничения")
        }
    }
}
