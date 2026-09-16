package io.aequicor.magicpaper.designsystem

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.use
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class PaperResearchSourceControlsTest {
    @Test fun groupSelectionShowsNonePartialAllAndEmptyAtBothTextScales() {
        for (scale in listOf(1f, 2f)) {
            val width = if (scale == 1f) 300 else 340
            val scene = onPaperUi { ImageComposeScene(width, if (scale == 1f) 240 else 460) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, scale)) {
                    PaperResearchSourceSelectionPreview()
                }
            } }
            try {
                onPaperUi {
                    repeat(6) { scene.render((it + 1) * 32_000_000L).close() }
                    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                    val nodes = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                    val checks = nodes.filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox }
                    assertEquals(listOf(androidx.compose.ui.state.ToggleableState.Off,
                        androidx.compose.ui.state.ToggleableState.Indeterminate,
                        androidx.compose.ui.state.ToggleableState.On,
                        androidx.compose.ui.state.ToggleableState.Off), checks.map { it.config[SemanticsProperties.ToggleableState] })
                    assertTrue(checks.last().config.contains(SemanticsProperties.Disabled))
                    val disclosures = nodes.filter { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                        .any { label -> label.startsWith("Свернуть:") || label.startsWith("Развернуть:") } }
                    assertEquals(4, disclosures.size)
                    checks.zip(disclosures).forEach { (check, disclosure) ->
                        assertTrue(check.boundsInRoot.right <= disclosure.boundsInRoot.left)
                        assertTrue(disclosure.boundsInRoot.right < width, "The trailing add action stays inside the pane")
                    }
                    File("build/reports/research-source-controls/group-selection-$scale.png").apply { parentFile.mkdirs() }
                        .writeBytes(scene.render(224_000_000L).use { image -> image.encodeToData()!!.use { it.bytes } })
                }
            } finally { onPaperUi { scene.close() } }
        }
    }

    @Test fun menuAppearsOnHoverAndKeyboardFocusWithoutMovingTheSourceTitle() {
        for (scale in listOf(1f, 2f)) {
        val scene = onPaperUi { ImageComposeScene(if (scale == 1f) 300 else 340, if (scale == 1f) 220 else 400) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, scale)) {
                PaperResearchSourceControlsPreview()
            }
        } }
        var frame = 0L
        fun render() = onPaperUi { repeat(6) { scene.render(++frame * 32_000_000L).close() } }
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        fun nodes() = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
        fun menu() = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Действия с источником") }
        fun title() = nodes().first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "system_design_replit.md · GitHub" } }
        fun pixels(name: String, rect: Rect): IntArray = onPaperUi {
            val bytes = scene.render(++frame * 32_000_000L).use { image -> image.encodeToData()!!.use { it.bytes } }
            val file = File("build/reports/research-source-controls/$name-$scale.png").apply { parentFile.mkdirs() }
            file.writeBytes(bytes)
            ImageIO.read(ByteArrayInputStream(bytes)).getRGB(rect.left.toInt(), rect.top.toInt(), rect.width.toInt(), rect.height.toInt(), null, 0, rect.width.toInt())
        }
        fun move(point: Offset) { onPaperUi { scene.sendPointerEvent(PointerEventType.Move, point, type = PointerType.Mouse) }; render() }
        fun press(key: Key) { onPaperUi {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown)); scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
        }; render() }
        try {
            render()
            val titleBounds = onPaperUi { title().boundsInRoot }
            val menuBounds = onPaperUi { menu().boundsInRoot }
            assertTrue(titleBounds.right >= menuBounds.right - 3f, "The title uses the width above the action lane")
            assertTrue(titleBounds.bottom <= menuBounds.top, "The menu is beside metadata, never over the title")
            val hidden = pixels("idle", menuBounds)
            move(titleBounds.center)
            assertFalse(hidden.contentEquals(pixels("hover", menuBounds)), "Hovering the source must reveal its menu")
            onPaperUi { assertEquals(titleBounds, title().boundsInRoot) }
            move(Offset(10f, 205f))
            assertContentEquals(hidden, pixels("pointer-left", menuBounds), "The menu must hide when the pointer leaves")
            repeat(6) { if (!onPaperUi { menu().config.getOrNull(SemanticsProperties.Focused) == true }) press(Key.Tab) }
            onPaperUi { assertTrue(menu().config.getOrNull(SemanticsProperties.Focused) == true) }
            assertFalse(hidden.contentEquals(pixels("keyboard-focus", menuBounds)))
            press(Key.Enter)
            onPaperUi { assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Открыть" } }) }
            onPaperUi {
                assertTrue(nodes().first { it.config.contains(SemanticsActions.RequestFocus) && walk(it).any { child ->
                    child.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Открыть" }
                } }.config[SemanticsActions.RequestFocus].action!!.invoke())
            }
            render()
            press(Key.Escape)
            onPaperUi { assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Открыть" } }, "Escape closes the source menu") }
            // Arrow keys on the group header toggle the disclosure, not the file action.
            onPaperUi {
                assertTrue(nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Свернуть: Общие для чата") }
                    .config[SemanticsActions.RequestFocus].action!!.invoke(), "Header accepts keyboard focus")
            }
            render()
            press(Key.DirectionLeft)
            onPaperUi { assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "system_design_replit.md · GitHub" } }) }
            press(Key.DirectionRight)
            onPaperUi { assertTrue(title().boundsInRoot.height > 0) }
        } finally { onPaperUi { scene.close() } }
        }
    }
}
