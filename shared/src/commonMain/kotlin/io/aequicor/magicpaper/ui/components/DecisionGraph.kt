package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

@Composable fun DecisionGraph(sourcePlan: Plan, selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val plan = remember(sourcePlan) { planningGraphProjection(sourcePlan) }
    var collapsed by remember(plan.id) { mutableStateOf(emptySet<String>()) }
    var zoom by remember(plan.id) { mutableStateOf(1f) }
    var pan by remember(plan.id) { mutableStateOf(Offset.Zero) }
    val structure = plan.tree.map { Triple(it.id, it.kind, it.children) }
    val positions = remember(structure, collapsed) { decisionPositions(plan.tree, collapsed) }
    val graphWidth = (positions.values.maxOfOrNull { it.x } ?: 0f) + 240f
    val graphHeight = (positions.values.maxOfOrNull { it.y } ?: 0f) + 124f
    val density = LocalDensity.current.density
    val scheme = MaterialTheme.colorScheme
    val stageNodes = remember(structure, plan.tree.map { it.stageId }) { plan.tree.associateBy { it.stageId ?: it.id } }
    val edgeSignature = plan.tree.map { it.id to it.dependsOn } to plan.milestones.map { it.id to it.dependsOn }
    val paths = remember(positions, edgeSignature, density) {
        buildList<Pair<Path, Boolean>> {
            fun edge(from: GraphPosition, to: GraphPosition, dependency: Boolean) {
                val a = Offset((from.x + 220) * density, (from.y + 44) * density)
                val b = Offset(to.x * density, (to.y + 44) * density)
                add(Path().apply { moveTo(a.x, a.y); cubicTo((a.x + b.x) / 2, a.y, (a.x + b.x) / 2, b.y, b.x, b.y) } to dependency)
            }
            plan.tree.forEach { n -> positions[n.id]?.let { from ->
                n.children.forEach { positions[it]?.let { to -> edge(from, to, false) } }
                n.dependsOn.forEach { positions[it]?.let { predecessor -> edge(predecessor, from, true) } }
                plan.milestones.firstOrNull { it.id == (n.stageId ?: n.id) }?.dependsOn?.forEach { dep ->
                    positions[stageNodes[dep]?.id]?.let { edge(it, from, true) }
                }
            } }
        }
    }
    val regularStroke = remember(density) { Stroke(2 * density) }
    val dependencyStroke = remember(density) { Stroke(2 * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7 * density, 5 * density))) }
    val selectedStages = remember(plan.tree, plan.milestones.map { it.id to it.dependsOn }) { DecisionCompiler.compile(plan).stageIds.toSet() }
    val activeNodes = remember(plan.tree) {
        val nodes = plan.tree.associateBy { it.id }; val active = mutableSetOf<String>()
        fun visit(id: String) {
            if (!active.add(id)) return
            val node = nodes[id] ?: return
            (if (node.kind == DecisionKind.CHOICE) listOfNotNull(node.selectedOptionId) else node.children).forEach(::visit)
        }
        plan.tree.filter { it.kind == DecisionKind.GOAL }.forEach { visit(it.id) }; active
    }
    val compositeStages = remember(plan.tree, selectedStages) {
        val nodes = plan.tree.associateBy { it.id }; val memo = mutableMapOf<String, Set<String>>()
        fun leaves(id: String, visiting: Set<String> = emptySet()): Set<String> {
            memo[id]?.let { return it }
            val node = nodes[id] ?: return emptySet()
            if (id in visiting) return emptySet()
            return (if (node.kind == DecisionKind.STAGE) setOf(node.stageId ?: node.id) else
                (if (node.kind == DecisionKind.CHOICE) listOfNotNull(node.selectedOptionId) else node.children)
                    .flatMap { leaves(it, visiting + id) }.toSet()).also { memo[id] = it }
        }
        plan.tree.forEach { leaves(it.id) }; memo
    }
    Column(modifier) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(scheme.surfaceVariant.copy(alpha = .3f))) {
            val viewportWidth = maxWidth.value; val viewportHeight = (maxHeight.value - 48).coerceAtLeast(1f)
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
                        paths.forEach { (path, dependency) ->
                            drawPath(path, if (dependency) scheme.tertiary else scheme.outline, style = if (dependency) dependencyStroke else regularStroke)
                        }
                    }
                    plan.tree.forEach { node -> positions[node.id]?.let { point ->
                        val stage = plan.milestones.firstOrNull { it.id == (node.stageId ?: node.id) }
                        val inactive = node.id !in activeNodes || (stage != null && stage.id !in selectedStages)
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
                        } } ?: run {
                            val children = plan.milestones.filter { it.id in compositeStages[node.id].orEmpty() }
                            val done = children.count { it.completed }
                            if (children.isNotEmpty() && done == children.size) "✔ Готово $done/${children.size}" else
                                (when (node.kind) { DecisionKind.CHOICE -> "◇ Выбор"; DecisionKind.OPTION -> "Вариант"; else -> "Этапы" }) + " · $done/${children.size}"
                        }
                        Column(Modifier.offset { IntOffset((point.x * density).roundToInt(), (point.y * density).roundToInt()) }
                            .size(220.dp, 108.dp).background(if (selected == node.id) scheme.primaryContainer else scheme.surface, MaterialTheme.shapes.medium)
                            .border(1.dp, if (selected == node.id) scheme.primary else scheme.outlineVariant, MaterialTheme.shapes.medium)
                            .graphicsLayer { alpha = if (inactive) .55f else 1f }.clickable { onSelect(node.id) }.padding(8.dp)) {
                            Text(node.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (inactive) "Не выбран" else status, style = MaterialTheme.typography.labelSmall)
                            stage?.assignment?.let { Text("${it.displayName.ifBlank { it.modelId }} · ${it.effort.shortLabel}", maxLines = 1, style = MaterialTheme.typography.labelSmall, overflow = TextOverflow.Ellipsis) }
                            if (node.children.isNotEmpty()) TextButton(onClick = { collapsed = if (node.id in collapsed) collapsed - node.id else collapsed + node.id }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                                Text(if (node.id in collapsed) "Раскрыть ▸" else "Свернуть ▾", style = MaterialTheme.typography.labelSmall)
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
            Text("${(zoom * 100).toInt()}% · линия: состав / варианты · пунктир: зависимость", style = MaterialTheme.typography.labelSmall)
        }
    }
}
