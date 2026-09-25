package io.aequicor.magicpaper.ui.components

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.aequicor.magicpaper.designsystem.PaperSurface
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.domain.EffortSelection
import io.aequicor.magicpaper.domain.ReasoningEffort
import io.aequicor.magicpaper.domain.ReasoningPresets
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class EffortControlRenderTest {
    @Test fun claudeMaxAndUltracodeAreSeparateAccessibleChoicesAtNarrowWidth() {
        for ((current, scale) in listOf(ReasoningEffort.MAX to 1f, ReasoningEffort.ULTRACODE to 2f)) {
            val selected = mutableListOf<EffortSelection>()
            ImageComposeScene(390, 440, density = Density(1f, scale)) {
                PaperTheme { PaperSurface {
                    renderEffortControl(ReasoningPresets.CLAUDE_CODE_EFFORT,
                        EffortSelection.of(current), selected::add)
                } }
            }.use { scene ->
                repeat(6) { scene.render(it * 16_000_000L).close() }
                val nodes = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                fun choice(label: String): SemanticsNode {
                    val text = nodes.first { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
                    return generateSequence(text) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
                }
                val max = choice("max")
                val ultracode = choice("ultracode")
                assertEquals(current == ReasoningEffort.MAX, max.config[SemanticsProperties.Selected])
                assertEquals(current == ReasoningEffort.ULTRACODE, ultracode.config[SemanticsProperties.Selected])
                assertTrue(max.boundsInRoot.right <= 390 && max.boundsInRoot.bottom <= 440)
                assertTrue(ultracode.boundsInRoot.right <= 390 && ultracode.boundsInRoot.bottom <= 440)
                val next = if (current == ReasoningEffort.MAX) ReasoningEffort.ULTRACODE else ReasoningEffort.MAX
                (if (next == ReasoningEffort.MAX) max else ultracode).config[SemanticsActions.OnClick].action!!.invoke()
                assertEquals(listOf(EffortSelection.of(next)), selected)
                val file = File("build/reports/effort-control/claude-${current.wire}-390-$scale.png").apply { parentFile.mkdirs() }
                scene.render(100_000_000L).use { image -> file.writeBytes(image.encodeToData()!!.use { it.bytes }) }
            }
        }
    }

    private fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
}
