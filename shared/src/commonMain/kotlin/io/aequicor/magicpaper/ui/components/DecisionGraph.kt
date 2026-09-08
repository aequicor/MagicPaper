package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*
import kotlin.math.roundToInt

data class GraphPosition(val x: Float, val y: Float)
/** Deterministic geometry, independent of reports/status events. */
fun decisionPositions(nodes: List<DecisionNode>, collapsed: Set<String>): Map<String, GraphPosition> {
    val byId = nodes.associateBy { it.id }
    val positions = linkedMapOf<String, GraphPosition>()
    var frontier = nodes.filter { it.kind == DecisionKind.GOAL }.map { it.id }
    var depth = 0
    while (frontier.isNotEmpty()) {
        val layer = frontier.distinct().filter { it !in positions && it in byId }
        layer.forEachIndexed { row, id -> positions[id] = GraphPosition(depth * 252f + 16, row * 124f + 16) }
        frontier = layer.filter { it !in collapsed }.flatMap { byId.getValue(it).children }
        depth++
    }
    return positions
}

/** A column follows every predecessor; parallel activities occupy separate rows. */
fun dependencyPositions(nodes: List<DecisionNode>, dependencies: Map<String, Set<String>>): Map<String, GraphPosition> {
    val stages = nodes.filter { it.kind == DecisionKind.STAGE }
    val remaining = stages.toMutableList()
    val ranks = mutableMapOf<String, Int>()
    while (remaining.isNotEmpty()) {
        val ready = remaining.filter { dependencies[it.stageId ?: it.id].orEmpty().all { dep -> dep in ranks } }
        if (ready.isEmpty()) break
        ready.forEach { node ->
            val id = node.stageId ?: node.id
            ranks[id] = 1 + (dependencies[id].orEmpty().maxOfOrNull { ranks.getValue(it) } ?: 0)
        }
        remaining.removeAll(ready.toSet())
    }
    val rows = mutableMapOf<Int, Int>()
    return buildMap {
        nodes.filter { it.kind == DecisionKind.GOAL }.forEach { put(it.id, GraphPosition(16f, 16f)) }
        stages.forEach { node ->
            val rank = ranks[node.stageId ?: node.id] ?: 1
            val row = rows[rank] ?: 0
            rows[rank] = row + 1
            put(node.id, GraphPosition(rank * 300f + 16f, row * 164f + 16f))
        }
    }
}

private fun points(value: Double): String {
    val rounded = kotlin.math.round(value * 100) / 100
    return "${rounded.toString().removeSuffix(".0").replace('.', ',')} ед."
}

@Composable fun DecisionGraph(sourcePlan: Plan, selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier, fitInitially: Boolean = false, onChooseOption: ((String, String) -> Unit)? = null) {
    var network by remember(sourcePlan.id) { mutableStateOf(false) }
    val projected = remember(sourcePlan) { planningGraphProjection(DecisionCompiler.migrate(sourcePlan)) }
    val schedule = remember(projected) { criticalPathSchedule(projected) }
    val showNetwork = network && schedule.errors.isEmpty()
    val plan = remember(projected, showNetwork, schedule) {
        if (!showNetwork) projected else {
            val root = projected.tree.first { it.kind == DecisionKind.GOAL }
            val stages = schedule.order.map { id ->
                val stage = projected.milestones.first { it.id == id }
                val node = projected.tree.first { it.kind == DecisionKind.STAGE && (it.stageId ?: it.id) == id }
                node.copy(title = stage.stageLabel(), children = emptyList(), dependsOn = emptyList())
            }
            projected.copy(tree = listOf(root.copy(children = stages.map { it.id })) + stages,
                milestones = projected.milestones.map { it.copy(dependsOn = schedule.dependencies[it.id].orEmpty().toList()) })
        }
    }
    var zoom by remember(plan.id) { mutableStateOf(1f) }
    var pan by remember(plan.id) { mutableStateOf(Offset.Zero) }
    val layout = remember(plan.tree, plan.milestones.map { it.id to it.dependsOn }) { planningNetworkLayout(plan) }
    val positions = layout.positions
    val graphWidth = layout.finish.x + 112f
    val graphHeight = maxOf((positions.values.maxOfOrNull { it.y } ?: 0f) + 180f, layout.start.y + 112f, layout.finish.y + 112f)
    val density = LocalDensity.current.density
    val scheme = MaterialTheme.colorScheme
    val branches = remember(projected.tree) { planningStageBranches(projected) }
    val activeStageIds = schedule.order.toSet()
    val activeGraphNodes = plan.tree.filter { it.kind == DecisionKind.STAGE && (it.stageId ?: it.id) in activeStageIds }.map { it.id }.toSet()
    val paths = remember(layout, density, activeGraphNodes) {
        buildList<Triple<Path, PlanningEdgeKind, Boolean>> {
            fun connect(a: Offset, b: Offset, kind: PlanningEdgeKind, active: Boolean, detour: Boolean = false) {
                val path = Path().apply {
                    moveTo(a.x, a.y)
                    if (detour && b.x > a.x) {
                        val corridor = a.y - 90 * density
                        val bend = 28 * density
                        cubicTo(a.x + bend, a.y, a.x + bend, corridor, a.x + 2 * bend, corridor)
                        cubicTo((a.x + b.x) / 2, corridor, (a.x + b.x) / 2, corridor, b.x - 2 * bend, corridor)
                        cubicTo(b.x - bend, corridor, b.x - bend, b.y, b.x, b.y)
                    } else cubicTo((a.x + b.x) / 2, a.y, (a.x + b.x) / 2, b.y, b.x, b.y)
                    moveTo(b.x - 8 * density, b.y - 5 * density); lineTo(b.x, b.y); lineTo(b.x - 8 * density, b.y + 5 * density)
                }
                add(Triple(path, kind, active))
            }
            fun input(p: GraphPosition) = Offset(p.x * density, (p.y + 78) * density)
            fun output(p: GraphPosition) = Offset((p.x + 220) * density, (p.y + 78) * density)
            val start = Offset((layout.start.x + 96) * density, (layout.start.y + 48) * density)
            val finish = Offset(layout.finish.x * density, (layout.finish.y + 48) * density)
            layout.edges.forEach { edge ->
                connect(output(positions.getValue(edge.from)), input(positions.getValue(edge.to)), edge.kind, edge.from in activeGraphNodes && edge.to in activeGraphNodes, positions.getValue(edge.to).x - positions.getValue(edge.from).x > 281f)
            }
            layout.sources.forEach { connect(start, input(positions.getValue(it)), PlanningEdgeKind.DEPENDENCY, it in activeGraphNodes) }
            layout.sinks.forEach { connect(output(positions.getValue(it)), finish, PlanningEdgeKind.DEPENDENCY, it in activeGraphNodes, layout.finish.x - positions.getValue(it).x > 281f) }
            if (positions.isEmpty()) connect(start, finish, PlanningEdgeKind.DEPENDENCY, true)
        }
    }
    val regularStroke = remember(density) { Stroke(2 * density) }
    val alternativeStroke = remember(density) { Stroke(2 * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7 * density, 5 * density))) }
    val selectedStages = schedule.order.toSet()
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!network, { network = false }, label = { Text("Все варианты") })
            FilterChip(network, { network = true }, label = { Text("Выбранный путь") })
        }
        Text(when {
            schedule.errors.isNotEmpty() -> schedule.errors.joinToString("; ")
            schedule.order.isEmpty() -> "Добавьте этапы в план"
            else -> "Выберите вариант над схемой · названия путей указаны на задачах"
        }, style = MaterialTheme.typography.labelSmall)
        if (!showNetwork) Column(Modifier.heightIn(max = 144.dp).verticalScroll(rememberScrollState())) {
            projected.tree.filter { it.kind == DecisionKind.CHOICE }.forEach { choice ->
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(choice.title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(180.dp))
                    choice.children.forEach { id -> projected.tree.firstOrNull { it.id == id }?.let { option ->
                        val enabled = remember(sourcePlan, choice.id, id) { runCatching { selectPlanningOption(sourcePlan, choice.id, id) }.isSuccess }
                        FilterChip(selected = choice.selectedOptionId == id,
                            onClick = { onChooseOption?.invoke(choice.id, id) },
                            enabled = onChooseOption != null && enabled,
                            label = { Text(option.title) })
                    } }
                }
            }
        }
        if (layout.cyclic) Text("В зависимостях есть цикл — исправьте связи задач", color = scheme.error, style = MaterialTheme.typography.labelSmall)

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(scheme.surfaceVariant.copy(alpha = .3f))) {
            val viewportWidth = maxWidth.value; val viewportHeight = (maxHeight.value - 48).coerceAtLeast(1f)
            LaunchedEffect(plan.id, fitInitially, viewportWidth, viewportHeight, showNetwork, graphWidth, graphHeight) {
                if (fitInitially) {
                    zoom = minOf(viewportWidth / graphWidth, viewportHeight / graphHeight).coerceIn(.005f, 1f)
                    pan = Offset.Zero
                }
            }
            Box(Modifier.fillMaxSize().padding(top = 48.dp).clipToBounds().onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionLeft -> { pan += Offset(60f, 0f); true }
                    Key.DirectionRight -> { pan -= Offset(60f, 0f); true }
                    Key.DirectionUp -> { pan += Offset(0f, 60f); true }
                    Key.DirectionDown -> { pan -= Offset(0f, 60f); true }
                    Key.MoveHome -> { zoom = minOf(viewportWidth / graphWidth, viewportHeight / graphHeight).coerceIn(.005f, 1f); pan = Offset.Zero; true }
                    else -> false
                }
            }.focusable().pointerInput(Unit) {
                detectTransformGestures { centroid, delta, factor, _ ->
                    val next = (zoom * factor).coerceIn(.005f, 2.5f)
                    pan = centroid - (centroid - pan) * (next / zoom) + delta; zoom = next
                }
            }) {
                Box(Modifier.wrapContentSize(unbounded = true, align = androidx.compose.ui.Alignment.TopStart).requiredSize(graphWidth.dp, graphHeight.dp).graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f); scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y
                }) {
                    Canvas(Modifier.fillMaxSize()) {
                        paths.forEach { (path, kind, active) ->
                            drawPath(path, (if (active) scheme.primary else scheme.outline).copy(alpha = if (active) 1f else .65f),
                                style = if (!active || kind == PlanningEdgeKind.ALTERNATIVE) alternativeStroke else regularStroke)
                        }
                    }
                    fun terminal(point: GraphPosition): Modifier = Modifier.offset {
                        IntOffset((point.x * density).roundToInt(), (point.y * density).roundToInt())
                    }.size(96.dp).background(scheme.surface, CircleShape).border(2.dp, scheme.primary, CircleShape)
                    Box(terminal(layout.start), contentAlignment = Alignment.Center) {
                        Text("Начало", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                    }
                    Box(terminal(layout.finish).padding(6.dp).border(1.dp, scheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                        Text("Завершение", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    }
                    plan.tree.distinctBy { if (it.kind == DecisionKind.STAGE) it.stageId ?: it.id else it.id }.forEach { node -> positions[node.id]?.let { point ->
                        val stage = plan.milestones.firstOrNull { it.id == (node.stageId ?: node.id) }
                        val inactive = !showNetwork && (node.stageId ?: node.id) !in selectedStages
                        val membership = branches[node.stageId ?: node.id]
                        val status = stage?.let { when (it.status) {
                            MilestoneStatus.ACTIVE -> "◷ " + when (it.attempts.lastOrNull()?.phase) {
                                AttemptPhase.VERIFYING -> "Проверка"
                                AttemptPhase.INTEGRATING -> "Объединение"
                                else -> "Работа"
                            }
                            MilestoneStatus.DONE -> "✔ Готово"
                            MilestoneStatus.FAILED -> "✕ Ошибка"
                            MilestoneStatus.SKIPPED -> "↷ Пропущен"
                            else -> "○ Ожидает"
                        } } ?: ""
                        Column(Modifier.offset { IntOffset((point.x * density).roundToInt(), (point.y * density).roundToInt()) }
                            .size(220.dp, 156.dp).background(if (selected == node.id) scheme.primaryContainer else if (!inactive && membership != null) scheme.primaryContainer.copy(alpha = .35f) else scheme.surface, MaterialTheme.shapes.medium)
                            .border(if (selected == node.id) 2.dp else 1.dp, if (selected == node.id || (!inactive && membership != null)) scheme.primary else scheme.outlineVariant, MaterialTheme.shapes.medium)
                            .graphicsLayer { alpha = if (inactive) .72f else 1f }
                            .clip(MaterialTheme.shapes.medium).clickable { onSelect(node.id) }.padding(8.dp)) {
                            Text(plan.milestones.firstOrNull { it.id == (node.stageId ?: node.id) }?.stageLabel() ?: node.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (membership != null) Text(
                                if (membership.shared) "↔ Общая для вариантов" else
                                    (if (inactive) "◇ " else "● ") + membership.labels.joinToString(" → ") { it.title },
                                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                                color = if (inactive) scheme.onSurfaceVariant else scheme.primary)
                            Text(if (inactive) "Альтернативная задача" else status, style = MaterialTheme.typography.labelSmall)
                            stage?.assignment?.let { Text("${it.displayName.ifBlank { it.modelId }} · ${it.effort.shortLabel}", maxLines = 1, style = MaterialTheme.typography.labelSmall, overflow = TextOverflow.Ellipsis) }
                            if (stage != null) {
                                val complexity = stage.complexityPoints ?: stage.assessment.complexity.takeIf { it > 0 }?.toDouble()
                                Text(complexity?.let { "Сложность: ${points(it)}" } ?: "Сложность пока не оценена", style = MaterialTheme.typography.labelSmall)
                            }

                        }
                    } }
                }
            }
            TextButton(modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd).background(scheme.surface, MaterialTheme.shapes.small),
                onClick = { zoom = minOf(viewportWidth / graphWidth, viewportHeight / graphHeight).coerceIn(.005f, 1f); pan = Offset.Zero }) { Text("Показать целиком") }
        }
        Row {
            TextButton(onClick = { zoom = (zoom / 1.2f).coerceAtLeast(.005f) }) { Text("−") }
            TextButton(onClick = { zoom = (zoom * 1.2f).coerceAtMost(2.5f) }) { Text("+") }
            Text(if (showNetwork) "${(zoom * 100).toInt()}% · стрелки: зависимости" else "${(zoom * 100).toInt()}% · сплошная: выбранный путь · пунктир: альтернатива · ↔ общая задача", style = MaterialTheme.typography.labelSmall)
        }
    }
}
