package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.util.Id
import kotlinx.datetime.*
import kotlin.math.roundToLong

internal fun usageNumber(value: Long?): String = value?.toString()?.reversed()?.chunked(3)?.joinToString(" ")?.reversed() ?: "Нет данных"
internal fun usageMetric(records: List<UsageRecord>, select: (TokenUsage) -> Long?): String {
    val values = records.mapNotNull { select(it.tokens) }
    return if (values.isEmpty()) "Нет данных" else usageNumber(values.sum()) + if (values.size < records.size) " (частично)" else ""
}
internal fun usageMoney(amount: Double): String {
    val units = (amount * 10000).roundToLong()
    return "${units / 10000}.${(units % 10000).toString().padStart(4, '0')}"
}

@Composable
internal fun UsageMenu(archive: UsageArchive, conversationId: String?, failure: String? = null) {
    var expanded by remember { mutableStateOf(false) }
    var currentOnly by rememberSaveable { mutableStateOf(false) }
    var todayOnly by rememberSaveable { mutableStateOf(false) }
    Box {
        PaperIconButton("Расходы", { expanded = !expanded }, selected = expanded) { PaperCoinIcon() }
        PaperMenuHost(expanded, { expanded = false }, Modifier.widthIn(max = 420.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperText("Расходы", role = PaperTextRole.TITLE)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PaperChoice(!currentOnly, { currentOnly = false }, "Всё приложение")
                    PaperChoice(currentOnly && conversationId != null, { currentOnly = true }, "Текущий чат", enabled = conversationId != null)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PaperChoice(!todayOnly, { todayOnly = false }, "Всё время")
                    PaperChoice(todayOnly, { todayOnly = true }, "Сегодня")
                }
                val startOfDay = Instant.fromEpochMilliseconds(Id.now()).toLocalDateTime(TimeZone.currentSystemDefault())
                    .date.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()
                val records = archive.records.filter { (!currentOnly || conversationId == null || it.scope.includes(conversationId)) && (!todayOnly || it.createdAt >= startOfDay) }
                if (records.isEmpty()) PaperText("Расходов пока нет") else {
                    UsageSummary(records)
                    PaperDivider()
                    records.groupBy { it.provider to it.model }.forEach { (source, rows) ->
                        PaperText(listOf(source.first, source.second).filter(String::isNotBlank).joinToString(" · "), role = PaperTextRole.LABEL)
                        var details by remember(source) { mutableStateOf(false) }
                        UsageSummary(rows, detailed = details)
                        PaperTextAction(onClick = { details = !details }) { PaperText(if (details) "Скрыть детали" else "Все показатели") }
                    }
                }
                PaperText("Учёт с ${Instant.fromEpochMilliseconds(archive.startedAt).toLocalDateTime(TimeZone.currentSystemDefault()).date}", role = PaperTextRole.LABEL)
                failure?.let { PaperText(it, color = LocalPaperColors.current.error) }
            }
        }
    }
}

@Composable
private fun UsageSummary(records: List<UsageRecord>, detailed: Boolean = true) {
    val models = records.filter { it.kind == UsageKind.MODEL }
    fun count(kind: UsageKind) = usageNumber(records.filter { it.kind == kind }.sumOf { it.requests })
    if (detailed) {
        UsageLine("Обращения к моделям", count(UsageKind.MODEL))
        UsageLine("Поисковые запросы", count(UsageKind.SEARCH))
        UsageLine("Запросы содержимого", usageNumber(records.sumOf { it.contentRequests }))
        UsageLine("Запрошено страниц", usageNumber(records.sumOf { it.pages }))
        UsageLine("Вход без кеша", usageMetric(models) { it.input })
        UsageLine("Ответы", usageMetric(models) { it.output })
        UsageLine("Чтение кеша", usageMetric(models) { it.cacheRead })
        UsageLine("Запись кеша", usageMetric(models) { it.cacheWrite })
        if (models.any { it.tokens.reasoning != null }) UsageLine("Из ответов: рассуждения", usageMetric(models) { it.reasoning })
        if (models.any { it.tokens.cachedOutput != null }) UsageLine("Из ответов: кешированные", usageMetric(models) { it.cachedOutput })
    } else UsageLine("Запросы", usageNumber(records.sumOf { it.requests }))
    if (models.isNotEmpty()) UsageLine("Всего токенов", usageMetric(models) { it.totalTokens })
    val costs = records.mapNotNull { it.cost }
    if (costs.isEmpty()) UsageLine("Стоимость", "Нет данных") else costs.groupBy { it.currency }.forEach { (currency, values) ->
        UsageLine(if (costs.size < records.size) "Известная стоимость" else "Стоимость", "${usageMoney(values.sumOf { it.amount })} $currency")
        values.groupBy { it.kind }.forEach { (kind, amounts) ->
            PaperText((if (kind == CostKind.ESTIMATED) "Оценка по тарифам: " else "По данным провайдера: ") +
                "${usageMoney(amounts.sumOf { it.amount })} $currency", role = PaperTextRole.LABEL)
        }
    }
    val unknown = records.count { it.cost == null && !it.subscription }
    if (unknown > 0) PaperText("Без данных о цене: $unknown", role = PaperTextRole.LABEL)
    if (records.any { it.subscription }) PaperText("ChatGPT: подписка; цена запроса не предоставляется", role = PaperTextRole.LABEL)
}

@Composable private fun UsageLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PaperText(label, Modifier.weight(1f), role = PaperTextRole.BODY)
        PaperText(value, role = PaperTextRole.BODY)
    }
}

@Composable
internal fun ContextUsageIndicator(snapshot: ContextUsageSnapshot?, model: String = snapshot?.model.orEmpty()) {
    var expanded by remember { mutableStateOf(false) }
    val fraction = snapshot?.fraction
    val label = fraction?.let { (if (snapshot.approximate) "≈" else "") + "${(it * 100).roundToLong()}%" } ?: "—"
    Box {
        PaperContextIndicator(fraction, label, { expanded = !expanded })
        PaperMenuHost(expanded, { expanded = false }, Modifier.widthIn(max = 340.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PaperText("Контекстное окно", role = PaperTextRole.TITLE)
                PaperText("Занято: ${usageNumber(snapshot?.used)} токенов")
                PaperText("Размер окна: ${usageNumber(snapshot?.limit)} токенов")
                if (model.isNotBlank()) PaperText(model)
                PaperText(if (snapshot?.approximate == true) "Приблизительная оценка движка" else if (fraction == null) "Ожидание данных движка" else "Последние данные движка", role = PaperTextRole.LABEL)
                PaperText("Неотправленный текст не учитывается", role = PaperTextRole.LABEL)
            }
        }
    }
}
