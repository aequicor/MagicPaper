package io.aequicor.magicpaper.designsystem

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.use
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class PaperSessionRowTest {
    @Test fun selectionDisclosureAndArrowKeysHaveSeparateActions() {
        val expanded = mutableStateOf(true)
        val focus = FocusRequester()
        val actionFocus = FocusRequester()
        var selections = 0
        var toggles = 0
        var actions = 0
        ImageComposeScene(240, 100) {
            PaperTheme {
                PaperSessionRow("Сессия", { selections++ }, Modifier.focusRequester(focus), selected = true,
                    expanded = expanded.value, onToggle = { expanded.value = !expanded.value; toggles++ },
                    actions = {
                        PaperIconButton("Действие", { actions++ }, Modifier.focusRequester(actionFocus)) { PaperText("⋯") }
                    })
            }
        }.use { scene ->
            var frame = 0L
            fun draw() { repeat(4) { Snapshot.sendApplyNotifications(); scene.render(++frame * 16_000_000L).close() } }
            fun nodes(): List<SemanticsNode> {
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            }
            fun press(key: Key) {
                scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
                scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
                draw()
            }
            draw()
            val row = nodes().single { it.config.getOrNull(SemanticsProperties.Selected) == true }
            assertTrue(row.config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals(1, selections)
            assertEquals(0, toggles)
            assertTrue(focus.requestFocus())
            draw()
            press(Key.DirectionLeft)
            assertFalse(expanded.value)
            press(Key.DirectionLeft)
            assertEquals(1, toggles)
            press(Key.DirectionRight)
            assertTrue(expanded.value)
            assertEquals(2, toggles)
            press(Key.Enter)
            assertEquals(2, selections)
            assertEquals(2, toggles)
            val disclosure = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Свернуть: Сессия") }
            assertTrue(disclosure.config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals(3, toggles)
            assertEquals(2, selections)
            assertTrue(actionFocus.requestFocus())
            draw()
            press(Key.Enter)
            assertEquals(1, actions, "Enter activates the focused action, not the session")
            assertEquals(2, selections)
        }
    }
}
