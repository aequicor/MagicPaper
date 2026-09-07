package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.io.File
import kotlin.test.*

class DecisionGraphRenderTest {
    @Test fun choosesAnAlternativeDirectlyOnTheGraph() {
        val current = mutableStateOf(Plan("routes", "project", "Реализация поиска", milestones = listOf(
            Milestone("a", "Готовая библиотека", complexityPoints = 3.0),
            Milestone("b", "Свой индекс", complexityPoints = 5.0),
            Milestone("c", "Ранжирование", complexityPoints = 3.0, dependsOn = listOf("b")),
            Milestone("end", "Проверка результатов", complexityPoints = 2.0),
        ), tree = listOf(
            DecisionNode("root", "Реализация поиска", DecisionKind.GOAL, listOf("choice", "end")),
            DecisionNode("choice", "Как реализовать поиск?", DecisionKind.CHOICE, listOf("library", "custom"), "library"),
            DecisionNode("library", "Использовать библиотеку", DecisionKind.OPTION, listOf("a")),
            DecisionNode("custom", "Собственная реализация", DecisionKind.OPTION, listOf("b", "c")),
            DecisionNode("a", "Готовая библиотека", DecisionKind.STAGE),
            DecisionNode("b", "Свой индекс", DecisionKind.STAGE),
            DecisionNode("c", "Ранжирование", DecisionKind.STAGE),
            DecisionNode("end", "Проверка результатов", DecisionKind.STAGE, dependsOn = listOf("choice")),
        )))
        val output = File("build/reports/planning").apply { mkdirs() }
        ImageComposeScene(1740, 740) {
            MagicPaperTheme { Surface { DecisionGraph(current.value, null, {}, Modifier.fillMaxSize(),
                onChooseOption = { choice, option -> current.value = selectPlanningOption(current.value, choice, option) }) } }
        }.use { scene ->
            repeat(3) { scene.render(it * 16_000_000L).close() }
            scene.render(64_000_000L).use { image ->
                File(output, "graph-alternatives-before.png").writeBytes(image.encodeToData()!!.use { it.bytes })
            }
            scene.sendPointerEvent(PointerEventType.Press, Offset(510f, 87f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(510f, 87f))
            repeat(3) { scene.render(80_000_000L + it * 16_000_000L).close() }
            assertEquals("custom", current.value.tree.first { it.id == "choice" }.selectedOptionId)
            assertEquals(setOf("b", "c", "end"), DecisionCompiler.compile(current.value).stageIds.toSet())
            scene.render(144_000_000L).use { image ->
                File(output, "graph-alternatives.png").writeBytes(image.encodeToData()!!.use { it.bytes })
            }
        }
    }

    @Test fun rendersCriticalAndNoncriticalBranches() {
        val stages = listOf(
            Milestone("a", "Подготовка", complexityPoints = 2.0),
            Milestone("b", "Реализация", complexityPoints = 5.0, dependsOn = listOf("a")),
            Milestone("c", "Документация", complexityPoints = 3.0, dependsOn = listOf("a")),
            Milestone("d", "Проверка", complexityPoints = 2.0, dependsOn = listOf("b", "c")),
        )
        val plan = Plan("cpm", "project", "План проекта", milestones = stages)
        val output = File("build/reports/planning").apply { mkdirs() }
        ImageComposeScene(1300, 620) {
            MagicPaperTheme { Surface { DecisionGraph(plan, null, {}, Modifier.fillMaxSize(), fitInitially = true) } }
        }.use { scene ->
            repeat(3) { scene.render(it * 16_000_000L).close() }
            scene.render(64_000_000L).use { image ->
                File(output, "graph-critical-path.png").writeBytes(image.encodeToData()!!.use { it.bytes })
            }
        }
    }

    private fun sample(count: Int = 6): Plan {
        val stages = (1..count).map { i -> Milestone("s$i", "Этап $i", status = if (i == 1) MilestoneStatus.ACTIVE else MilestoneStatus.PENDING,
            assignment = StageAssignment("source", "Модель $i", EffortSelection.of(ReasoningEffort.MEDIUM))) }
        return Plan("plan", "project", "Рабочий проект", milestones = stages,
            tree = listOf(DecisionNode("root", "Рабочий проект", DecisionKind.GOAL, stages.map { it.id })) + stages.map { DecisionNode(it.id, it.title, DecisionKind.STAGE) })
    }
    @Test fun rendersDesktopMobileAndTwoHundredNodesAndSelectsNode() {
        val output = File("build/reports/planning").apply { mkdirs() }
        for ((width, height, count) in listOf(Triple(1000, 600, 6), Triple(390, 600, 6), Triple(1000, 600, 200))) {
            val selected = mutableStateOf<String?>(null)
            ImageComposeScene(width, height) {
                MagicPaperTheme { Surface { DecisionGraph(sample(count), selected.value, { selected.value = it }, Modifier.fillMaxSize()) } }
            }.use { scene ->
                repeat(3) { scene.render(it * 16_000_000L).close() }
                scene.sendPointerEvent(PointerEventType.Press, Offset(340f, 180f))
                scene.sendPointerEvent(PointerEventType.Release, Offset(340f, 180f))
                scene.render(64_000_000L).close()
                assertEquals("s1", selected.value)
                val bytes = scene.render(80_000_000L).use { image -> image.encodeToData()!!.use { it.bytes } }
                assertTrue(bytes.size > 1000)
                File(output, "graph-${width}x$height-$count.png").writeBytes(bytes)
                // Fit must work for deep/tall graphs as well as initial viewport selection.
                scene.sendPointerEvent(PointerEventType.Press, Offset(width - 90f, 90f))
                scene.sendPointerEvent(PointerEventType.Release, Offset(width - 90f, 90f))
                scene.render(96_000_000L).use { image -> File(output, "graph-fit-${width}-$count.png").writeBytes(image.encodeToData()!!.use { it.bytes }) }
            }
        }
    }
}
