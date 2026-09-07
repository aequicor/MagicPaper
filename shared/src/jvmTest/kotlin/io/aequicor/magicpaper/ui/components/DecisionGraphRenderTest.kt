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
                scene.sendPointerEvent(PointerEventType.Press, Offset(300f, 65f))
                scene.sendPointerEvent(PointerEventType.Release, Offset(300f, 65f))
                scene.render(64_000_000L).close()
                assertEquals("s1", selected.value)
                val bytes = scene.render(80_000_000L).use { image -> image.encodeToData()!!.use { it.bytes } }
                assertTrue(bytes.size > 1000)
                File(output, "graph-${width}x$height-$count.png").writeBytes(bytes)
                // Fit must work for deep/tall graphs as well as initial viewport selection.
                scene.sendPointerEvent(PointerEventType.Press, Offset(width - 90f, 24f))
                scene.sendPointerEvent(PointerEventType.Release, Offset(width - 90f, 24f))
                scene.render(96_000_000L).use { image -> File(output, "graph-fit-${width}-$count.png").writeBytes(image.encodeToData()!!.use { it.bytes }) }
            }
        }
    }
}
