package io.aequicor.magicpaper.designsystem

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class PaperComposerCornerTest {
    @Test fun cornerRevealsOnHoverAndKeyboardFocusAndTogglesWithoutMoving() {
        for (scale in listOf(1f, 2f)) {
            val focus = FocusRequester()
            lateinit var inputMode: InputModeManager
            val scene = onPaperUi { ImageComposeScene(360, 200) {
                inputMode = LocalInputModeManager.current
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    PaperComposerCornerPreview(Modifier.focusRequester(focus))
                }
            } }
            var frame = 0L
            fun render() = onPaperUi { repeat(8) { scene.render(++frame * 32_000_000L).close() } }
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            fun button() = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.single {
                it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(SemanticsProperties.ContentDescription)
                    .orEmpty().any { label -> label.endsWith("поле ввода") }
            }
            fun capture(name: String): IntArray = onPaperUi {
                val bounds = button().boundsInRoot
                val bytes = scene.render(++frame * 32_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } }
                File("build/reports/composer-corner/$name-$scale.png").apply { parentFile.mkdirs(); writeBytes(bytes) }
                ImageIO.read(ByteArrayInputStream(bytes)).getRGB(bounds.left.toInt(), bounds.top.toInt(),
                    bounds.width.toInt(), bounds.height.toInt(), null, 0, bounds.width.toInt())
            }
            try {
                render()
                val bounds = onPaperUi { button().boundsInRoot }
                val idle = capture("idle")
                onPaperUi { scene.sendPointerEvent(PointerEventType.Move, bounds.center, type = PointerType.Mouse) }
                render()
                val hovered = capture("hover")
                assertFalse(idle.contentEquals(hovered), "The arc becomes a visible circular action")
                onPaperUi {
                    scene.sendPointerEvent(PointerEventType.Press, bounds.center, type = PointerType.Mouse, button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, bounds.center, type = PointerType.Mouse, button = PointerButton.Primary)
                }
                render()
                onPaperUi {
                    assertEquals("Развёрнуто", button().config[SemanticsProperties.StateDescription])
                    assertEquals(bounds, button().boundsInRoot, "Hover and expansion keep the corner target stable")
                }
                assertFalse(hovered.contentEquals(capture("expanded-hover")), "The arrows now indicate collapse")
                onPaperUi { scene.sendPointerEvent(PointerEventType.Move, Offset(2f, 2f), type = PointerType.Mouse) }
                render()
                assertContentEquals(idle, capture("leave"), "Mouse focus does not keep the action visible after leaving")
                onPaperUi {
                    inputMode.requestInputMode(InputMode.Keyboard)
                    focus.requestFocus()
                    scene.sendKeyEvent(KeyEvent(Key.DirectionRight, KeyEventType.KeyDown))
                    scene.sendKeyEvent(KeyEvent(Key.DirectionRight, KeyEventType.KeyUp))
                }
                render()
                assertFalse(idle.contentEquals(capture("keyboard-focus")))
                onPaperUi {
                    scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown))
                    scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyUp))
                }
                render()
                onPaperUi { assertEquals("Свёрнуто", button().config[SemanticsProperties.StateDescription]) }
            } finally { onPaperUi { scene.close() } }
        }
    }
}
