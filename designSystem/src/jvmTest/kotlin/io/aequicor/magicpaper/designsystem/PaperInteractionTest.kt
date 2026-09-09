package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class PaperInteractionTest {
    @Test fun fullShapeFeedbackClearsWhenDisabledAndNeverActivatesBusyControls() {
        val state = mutableStateOf(PaperControlState.NORMAL)
        var clicks = 0
        ImageComposeScene(240, 120) {
            PaperTheme {
                PaperSurface(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(20.dp)) {
                        PaperButton("Сохранить", { clicks++ }, Modifier.width(180.dp).height(40.dp), state = state.value)
                    }
                }
            }
        }.use { scene ->
            var frame = 0L
            fun snapshot(): BufferedImage {
                repeat(4) { scene.render(++frame * 16_000_000).close() }
                return scene.render(++frame * 16_000_000).use { image ->
                    image.encodeToData()!!.use { ImageIO.read(ByteArrayInputStream(it.bytes)) }
                }
            }
            fun pointer(kind: PointerEventType) = scene.sendPointerEvent(kind, Offset(24f, 40f), type = PointerType.Mouse)
            val normal = snapshot()
            pointer(PointerEventType.Move)
            val hovered = snapshot()
            assertNotEquals(normal.getRGB(24, 40), hovered.getRGB(24, 40))
            assertEquals(hovered.getRGB(24, 40), hovered.getRGB(194, 40), "Hover fills both padded edges")
            assertEquals(normal.getRGB(20, 20), hovered.getRGB(20, 20), "Rounded corner stays outside the fill")
            pointer(PointerEventType.Press)
            val pressed = snapshot()
            assertNotEquals(hovered.getRGB(24, 40), pressed.getRGB(24, 40))
            pointer(PointerEventType.Release)
            snapshot()
            assertEquals(1, clicks)
            for (blocked in listOf(PaperControlState.DISABLED, PaperControlState.BUSY)) {
                state.value = blocked
                val before = snapshot()
                pointer(PointerEventType.Press)
                pointer(PointerEventType.Release)
                val after = snapshot()
                assertEquals(before.getRGB(24, 40), after.getRGB(24, 40))
                assertEquals(1, clicks)
            }
        }
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    @Test fun contextControlDoesNotKeepAnOutlineAfterMouseClick() {
        ImageComposeScene(240, 120) {
            PaperTheme {
                PaperSurface(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(20.dp)) {
                        PaperContextIndicator(.5f, "50%", {}, Modifier.width(180.dp).height(40.dp))
                    }
                }
            }
        }.use { scene ->
            var frame = 0L
            fun snapshot(): BufferedImage {
                repeat(4) { scene.render(++frame * 16_000_000).close() }
                return scene.render(++frame * 16_000_000).use { image ->
                    image.encodeToData()!!.use { ImageIO.read(ByteArrayInputStream(it.bytes)) }
                }
            }
            snapshot()
            for (kind in listOf(PointerEventType.Move, PointerEventType.Press, PointerEventType.Release)) {
                scene.sendPointerEvent(kind, Offset(180f, 40f), type = PointerType.Mouse)
                snapshot()
            }
            scene.sendPointerEvent(PointerEventType.Move, Offset(230f, 100f), type = PointerType.Mouse)
            val mouse = snapshot()
            assertEquals(mouse.getRGB(21, 40), mouse.getRGB(27, 40), "Mouse focus must not leave a perimeter")
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyUp))
            val keyboard = snapshot()
            assertNotEquals(keyboard.getRGB(21, 40), keyboard.getRGB(27, 40), "Keyboard focus stays visible")
        }
    }

    @Test fun renderPlatformCatalogues() {
        for (platform in listOf(PaperPlatform.MACOS, PaperPlatform.WINDOWS)) {
            val height = if (platform == PaperPlatform.MACOS) 28.dp else 32.dp
            val policy = PaperPlatformPolicy.Fallback.copy(platform = platform, density = PaperDensity(height, height, height + 2.dp, 16.dp))
            ImageComposeScene(780, 520) {
                PaperTheme {
                    CompositionLocalProvider(LocalPaperPlatformPolicy provides policy) {
                        PaperSurface(Modifier.fillMaxSize(), PaperSurfaceKind.CANVAS) {
                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                PaperText("MagicPaper · ${platform.name}", role = PaperTextRole.HEADLINE)
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    PaperButton("Создать", {})
                                    PaperButton("Выбрано", {}, kind = PaperButtonKind.SECONDARY)
                                    PaperButton("Удалить", {}, kind = PaperButtonKind.DESTRUCTIVE)
                                    PaperButton("Недоступно", {}, enabled = false)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    PaperButton("Наведение", {}, state = PaperControlState.HOVER)
                                    PaperButton("Нажатие", {}, state = PaperControlState.PRESSED)
                                    PaperButton("Фокус", {}, state = PaperControlState.FOCUSED)
                                    PaperButton("Загрузка", {}, busy = true)
                                }
                                PaperField("Новый проект", {}, "Название", Modifier.width(400.dp))
                                PaperField("", {}, "Имя", Modifier.width(400.dp), errorMessage = "Введите имя")
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    PaperChoice(true, {}, "Проекты")
                                    PaperChoice(false, {}, "Заметки")
                                    PaperToggle(true, {})
                                    PaperCheck(true, {})
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    PaperPanel(color = LocalPaperColors.current.successSurface) { PaperText("Готово", Modifier.padding(12.dp), color = LocalPaperColors.current.success) }
                                    PaperPanel(color = LocalPaperColors.current.accentSurface) { PaperText("Избранное", Modifier.padding(12.dp)) }
                                }
                            }
                        }
                    }
                }
            }.use { scene ->
                repeat(12) { scene.render((it + 1) * 16_000_000L).close() }
                val file = File("build/reports/paper-controls/${platform.name.lowercase()}.png")
                file.parentFile.mkdirs()
                scene.render(240_000_000L).use { image -> image.encodeToData()!!.use { file.writeBytes(it.bytes) } }
            }
        }
    }
}
