package io.aequicor.magicpaper.designsystem

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class PaperNoteAddIconTest {
    @Test fun iconAndLabelAreCenteredTogetherAtBothTextScales() {
        for (scale in listOf(1f, 2f)) {
            val scene = onPaperUi { ImageComposeScene(if (scale == 1f) 300 else 340, 300) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) { PaperNoteAddPreview() }
            } }
            try {
                onPaperUi {
                    repeat(8) { scene.render(it * 32_000_000L).close() }
                    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                    val nodes = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                    val icon = nodes.single { it.config.getOrNull(SemanticsProperties.TestTag) == "chat-create-icon" }
                    val label = nodes.single { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Новый чат" } }
                    val button = nodes.single { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Новый чат") }
                    assertEquals(16f, icon.boundsInRoot.width, .5f)
                    assertEquals((icon.boundsInRoot.left + label.boundsInRoot.right) / 2, button.boundsInRoot.center.x, 1f)
                    assertEquals(icon.boundsInRoot.center.y, label.boundsInRoot.center.y, 1f)
                    assertTrue(icon.boundsInRoot.right < label.boundsInRoot.left)
                    assertFalse(icon.config.contains(SemanticsProperties.ContentDescription), "The button supplies one accessible action name")
                    assertTrue(nodes.any { it.config.contains(SemanticsProperties.Disabled) })
                    File("build/reports/note-add/actions-$scale.png").apply { parentFile.mkdirs() }
                        .writeBytes(scene.render(280_000_000L).use { image -> image.encodeToData()!!.use { it.bytes } })
                }
            } finally { onPaperUi { scene.close() } }
        }
        // Same real controls side by side at desktop/Retina density for visual acceptance.
        for (density in listOf(1f, 2f)) {
            val scene = onPaperUi { ImageComposeScene((320 * density).toInt(), (170 * density).toInt()) {
                CompositionLocalProvider(LocalDensity provides Density(density, 1f)) { PaperOutlineIconsPreview() }
            } }
            try {
                onPaperUi {
                    repeat(8) { scene.render(it * 32_000_000L).close() }
                    File("build/reports/note-add/outline-comparison-$density.png").apply { parentFile.mkdirs() }
                        .writeBytes(scene.render().use { image -> image.encodeToData()!!.use { it.bytes } })
                }
            } finally { onPaperUi { scene.close() } }
        }
    }
}
