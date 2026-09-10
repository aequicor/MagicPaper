package io.aequicor.magicpaper.designsystem

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    @Test fun attachmentThumbnailPublishesLoadingReadyAndErrorStates() {
        ImageComposeScene(320, 180) { PaperTheme {
            androidx.compose.foundation.layout.Column {
                PaperAttachmentThumbnail("load.png", "load.png", PaperAttachmentThumbnailState.LOADING, onRemove = null)
                PaperAttachmentThumbnail("ready.png", "ready.png", PaperAttachmentThumbnailState.READY, ImageBitmap(1, 1), onRemove = null)
                PaperAttachmentThumbnail("error.png", "error.png", PaperAttachmentThumbnailState.ERROR, onRemove = null)
            }
        } }.use { scene ->
            scene.render(16_000_000).close()
            val labels = scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            assertTrue(labels.any { it.contains("Загрузка миниатюры") })
            assertTrue(labels.any { it.contains("Миниатюра готова") })
            assertTrue(labels.any { it.contains("Миниатюра недоступна") })
        }
    }

    @Test fun attachmentThumbnailExposesStateAndOpenAction() {
        var opens = 0
        var removes = 0
        ImageComposeScene(320, 120) { PaperTheme {
            PaperAttachmentThumbnail(
                label = "picture.png · 1 Б", description = "picture.png",
                state = PaperAttachmentThumbnailState.READY, bitmap = ImageBitmap(1, 1),
                onRemove = { removes++ }, onOpen = { opens++ },
            )
        } }.use { scene ->
            scene.render(16_000_000).close()
            val open = scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Открыть picture.png")
            }
            val remove = scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Удалить picture.png · 1 Б")
            }
            assertTrue(open.config[SemanticsActions.OnClick].action?.invoke() == true)
            assertTrue(remove.config[SemanticsActions.OnClick].action?.invoke() == true)
            assertEquals(1, opens)
            assertEquals(1, removes)
        }
    }

    @Test fun contextIndicatorExposesProgressAndSupportsKeyboard() {
        val focus = FocusRequester()
        val fraction = mutableStateOf<Float?>(.5f)
        var clicks = 0
        ImageComposeScene(320, 120) { PaperTheme {
            PaperContextIndicator(fraction.value, if (fraction.value == null) "—" else "50%", { clicks++ }, Modifier.focusRequester(focus))
        } }.use { scene ->
            scene.render(16_000_000).close()
            fun control() = scene.nodes().first { it.config.getOrNull(SemanticsProperties.StateDescription) != null }
            assertEquals(.5f, control().config[SemanticsProperties.ProgressBarRangeInfo].current)
            assertTrue(focus.requestFocus())
            scene.render(32_000_000).close()
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyUp))
            scene.sendKeyEvent(KeyEvent(Key.Spacebar, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.Spacebar, KeyEventType.KeyUp))
            assertEquals(2, clicks)
            fraction.value = null
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            scene.render(48_000_000).close()
            assertEquals("—", control().config[SemanticsProperties.StateDescription])
            assertEquals(null, control().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo))
        }
    }

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

    @Test
    fun fieldKeepsItsGuidanceWhenValidationFails() {
        ImageComposeScene(320, 160) {
            PaperTheme { PaperField("", {}, label = "Сложность", supportingText = "Введите положительное число", errorMessage = "Некорректное значение") }
        }.use { scene ->
            scene.render(16_000_000).close()
            val text = scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            assertTrue("Введите положительное число" in text)
            assertTrue("Некорректное значение" in text)
        }
    }
}
