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
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class PaperResearchSourcesDisclosureTest {
    @Test fun sourcesStartCollapsedAndRevealTheirActionWithoutMovingTheCount() {
        for ((width, scale) in listOf(360 to 1f, 280 to 2f)) {
            val focus = FocusRequester()
            lateinit var inputMode: InputModeManager
            val scene = onPaperUi { ImageComposeScene(width, 400) {
                inputMode = LocalInputModeManager.current
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    PaperResearchSourcesDisclosurePreview(Modifier.focusRequester(focus))
                }
            } }
            var frame = 0L
            fun settle() = onPaperUi { repeat(8) { scene.render(++frame * 32_000_000L).close() } }
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            fun nodes() = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            fun action() = nodes().single { it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { label -> label.endsWith("источники ответа") } }
            fun count() = nodes().single { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "14" } }
            fun hasSource() = nodes().any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Figma: Prompt to App" } }
            fun capture(name: String) = onPaperUi {
                scene.render(++frame * 32_000_000L).use { image -> image.encodeToData()!!.use { data ->
                    File("build/reports/answer-sources/$name-$scale.png").apply { parentFile.mkdirs(); writeBytes(data.bytes) }
                } }
            }
            try {
                settle()
                val headerBounds = onPaperUi { action().boundsInRoot }
                val countBounds = onPaperUi { count().boundsInRoot }
                onPaperUi {
                    assertFalse(hasSource())
                    assertTrue(countBounds.right < width)
                    assertEquals("Свёрнуто, источников: 14", action().config[SemanticsProperties.StateDescription])
                }
                capture("collapsed")
                onPaperUi { scene.sendPointerEvent(PointerEventType.Move, headerBounds.center, type = PointerType.Mouse) }
                settle()
                capture("hover")
                onPaperUi {
                    assertEquals(countBounds, count().boundsInRoot)
                    scene.sendPointerEvent(PointerEventType.Press, headerBounds.center, type = PointerType.Mouse, button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, headerBounds.center, type = PointerType.Mouse, button = PointerButton.Primary)
                }
                settle()
                onPaperUi { assertTrue(hasSource()); assertEquals(headerBounds, action().boundsInRoot) }
                capture("expanded")
                onPaperUi {
                    scene.sendPointerEvent(PointerEventType.Move, Offset(2f, 2f), type = PointerType.Mouse)
                    inputMode.requestInputMode(InputMode.Keyboard)
                    focus.requestFocus()
                }
                settle()
                capture("keyboard")
                onPaperUi {
                    scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown))
                    scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyUp))
                }
                settle()
                onPaperUi { assertFalse(hasSource()) }
            } finally { onPaperUi { scene.close() } }
        }
    }
}
