package io.aequicor.magicpaper.designsystem

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class PaperContextIndicatorRenderTest {
    @Test fun percentagesStayCenteredAndFitInsideTheControlAtBothTextScales() {
        for (scale in listOf(1f, 2f)) {
            val width = if (scale == 1f) 360 else 500
            val height = if (scale == 1f) 150 else 200
            val scene = onPaperUi { ImageComposeScene(width, height) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    PaperContextIndicatorPreview()
                }
            } }
            try {
                onPaperUi {
                    repeat(6) { scene.render((it + 1) * 32_000_000L).close() }
                    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                    val nodes = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                    val controls = nodes.filter { it.config.getOrNull(SemanticsProperties.StateDescription) != null }
                    assertEquals(8, controls.size)
                    controls.forEach { control ->
                        val label = walk(control).single { it.config.getOrNull(SemanticsProperties.Text) != null }
                        val bounds = control.boundsInRoot
                        assertEquals(bounds.center.x, label.boundsInRoot.center.x, .5f, "The percentage is inside, not beside, its contour")
                        assertEquals(bounds.center.y, label.boundsInRoot.center.y, .5f)
                        assertTrue(label.boundsInRoot.width < bounds.width)
                        assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.bottom <= height)
                    }
                    val small = controls.single { it.config[SemanticsProperties.StateDescription] == "0%" }
                    val wide = controls.single { it.config[SemanticsProperties.StateDescription] == "≈100%" }
                    assertTrue(wide.boundsInRoot.width > small.boundsInRoot.width, "Long percentages expand the contour")
                    val file = File("build/reports/context-indicator/context-$scale.png").apply { parentFile.mkdirs() }
                    scene.render(240_000_000L).use { image -> image.encodeToData()!!.use { file.writeBytes(it.bytes) } }
                }
            } finally { onPaperUi { scene.close() } }
        }
    }
}
