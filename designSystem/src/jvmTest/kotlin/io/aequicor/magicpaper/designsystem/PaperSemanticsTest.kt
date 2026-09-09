package io.aequicor.magicpaper.designsystem

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class PaperSemanticsTest {
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }

    @Test
    fun buttonHasAccessibleLabelAndSemanticKeyboardActivation() {
        var activations = 0
        ImageComposeScene(320, 120) { PaperTheme { PaperButton("Сохранить", { activations++ }, accessibilityLabel = "Сохранить изменения") } }.use { scene ->
            scene.render(16_000_000).close()
            val button = scene.nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Сохранить изменения") }
            val action = assertNotNull(button.config.getOrNull(SemanticsActions.OnClick))
            assertEquals(true, action.action?.invoke())
            assertEquals(1, activations)
        }
    }

    @Test
    fun focusedButtonActivatesFromEnterAndSpace() {
        var activations = 0
        val focusRequester = FocusRequester()
        ImageComposeScene(320, 120) { PaperTheme { PaperButton("Сохранить", { activations++ }, focusRequester = focusRequester) } }.use { scene ->
            scene.render(16_000_000).close()
            fun key(value: Key, type: KeyEventType) = KeyEvent(key = value, type = type)
            assertTrue(focusRequester.requestFocus(), "Test must focus the PaperButton through the public API")
            scene.render(32_000_000).close()
            assertTrue(scene.sendKeyEvent(key(Key.Enter, KeyEventType.KeyDown)))
            scene.render(48_000_000).close()
            assertEquals(1, activations, "Enter must activate the Tab-focused PaperButton")

            assertTrue(scene.sendKeyEvent(key(Key.Spacebar, KeyEventType.KeyDown)))
            assertTrue(scene.sendKeyEvent(key(Key.Spacebar, KeyEventType.KeyUp)))
            scene.render(64_000_000).close()
            assertEquals(2, activations, "Space must activate the focused PaperButton")
        }
    }

    @Test
    fun focusAnchorRestoresFocusAfterOverlayDismissal() {
        var focused = false
        val restorer = PaperFocusRestorer()
        val dialogOpen = mutableStateOf(true)
        ImageComposeScene(320, 120) {
            PaperTheme {
                PaperFocusAnchor(restorer, "opener", Modifier.onFocusChanged { focused = it.isFocused }) {
                    PaperText("Открыть")
                }
                if (dialogOpen.value) {
                    PaperDialog("Подтверждение", onDismissRequest = { dialogOpen.value = false }, focusRestorer = restorer) {
                        PaperText("Содержимое")
                    }
                }
            }
        }.use { scene ->
            scene.render(16_000_000).close()
            val dismiss = scene.nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Закрыть") }
            assertTrue(dismiss.config[SemanticsActions.OnClick].action?.invoke() == true)
            scene.render(32_000_000).close()
            assertTrue(!dialogOpen.value)
            assertTrue(focused)
        }
    }

    @Test
    fun fieldPublishesItsValidationErrorToAccessibility() {
        ImageComposeScene(320, 120) {
            PaperTheme { PaperField("", {}, label = "Имя", errorMessage = "Введите имя") }
        }.use { scene ->
            scene.render(16_000_000).close()
            assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.Error) == "Введите имя" })
        }
    }
}
