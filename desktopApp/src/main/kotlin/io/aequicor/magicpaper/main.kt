package io.aequicor.magicpaper

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.aequicor.magicpaper.ui.window.DesktopWindowChrome
import io.aequicor.magicpaper.ui.window.LocalWindowChrome
import io.aequicor.magicpaper.ui.window.LocalWindowScope
import io.aequicor.magicpaper.ui.window.LocalWindowTitleBarInsets
import java.awt.Frame
import java.awt.Image
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.imageio.ImageIO
import javax.swing.JFrame

/**
 * Иконки окна и дока: все разрешения, ОС выберет нужное.
 * Генерируются скриптом assets/icon/gen_icons.py.
 */
private val AppIcons: List<Image> by lazy {
    listOf(16, 32, 48, 128, 256).mapNotNull { size ->
        runCatching {
            ImageIO.read(object {}.javaClass.getResourceAsStream("/icons/app_$size.png"))
        }.getOrNull()
    }
}

private fun FrameWindowScope.setAppIcons() {
    if (AppIcons.isNotEmpty()) (window as? Frame)?.iconImages = AppIcons
}

/** macOS: нативные декорации + прозрачный тайтлбар; иначе — кастомный хром без тайтлбара. */
private fun isMacOs(): Boolean = System.getProperty("os.name").lowercase().startsWith("mac")

/** Нативная высота тайтлбара macOS (unified title bar). */
private val MacTitleBarHeight = 28.dp

/** Ширина зоны «светофора»: три кнопки по ~12pt с шагом 20, отступ 7pt + запас. */
private val MacTrafficLightsWidth = 78.dp

fun main() = application {
    val state = rememberWindowState(width = 1000.dp, height = 700.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = "MagicPaper — Шалость удалась",
        state = state,
        // Windows/Linux: окно без системных декораций — тайтлбар, кнопки ─ ▢ ✕
        // и перетаскивание рисует само приложение; ресайз за края остаётся
        // (встроен в недекорированные окна Compose).
        // macOS: декорации нативные — скругления, снап к краям и «светофор»
        // сохраняются; убираем только видимость тайтлбара (см. ниже).
        undecorated = !isMacOs(),
    ) {
        setAppIcons()
        val chrome = remember(window) { if (isMacOs()) null else DesktopWindowChrome(window) }
        val titleBarInsets = rememberMacTitleBarInsets()
        // Фон окна в цвет приложения — без белой вспышки в углах при ресайзе.
        window.background = java.awt.Color(0xF5, 0xEF, 0xE3)
        CompositionLocalProvider(
            LocalWindowChrome provides chrome,
            LocalWindowScope provides this,
            LocalWindowTitleBarInsets provides titleBarInsets,
        ) {
            App()
        }
    }
}

/**
 * macOS: прозрачный нативный тайтлбар — контент рисуется под ним (edge-to-edge),
 * при этом «светофор», скругления углов и снап к краям экрана остаются нативными.
 * Возвращает инсеты: top — высота тайтлбара (0 в полноэкранном режиме),
 * start — зона «светофора», чтобы под ним не оказалось интерактивных элементов.
 */
@Composable
private fun FrameWindowScope.rememberMacTitleBarInsets(): PaddingValues {
    if (!isMacOs()) return PaddingValues()
    var insets by remember { mutableStateOf(macInsets(fullScreen = false)) }
    fun read() {
        // В полноэкранном режиме окно занимает ровно весь экран (с точностью
        // до пикселя), меню и тайтлбар прячутся — инсеты обнуляем.
        val bounds = window.bounds
        val screen = window.graphicsConfiguration?.bounds
        val fullScreen = screen != null &&
            bounds.width == screen.width && bounds.height == screen.height
        insets = macInsets(fullScreen)
    }
    DisposableEffect(window) {
        val rootPane = (window as? JFrame)?.rootPane
        fun applyTitleBarStyle() {
            // JBR-свойства: контент на всю высоту окна, тайтлбар прозрачный,
            // текст заголовка скрыт (заголовок рисует сам интерфейс).
            rootPane?.putClientProperty("apple.awt.fullWindowContent", true)
            rootPane?.putClientProperty("apple.awt.transparentTitleBar", true)
            rootPane?.putClientProperty("apple.awt.windowTitleVisible", false)
            rootPane?.revalidate()
            rootPane?.repaint()
        }
        applyTitleBarStyle()
        val listener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                applyTitleBarStyle()
                read()
            }

            override fun componentMoved(e: ComponentEvent) = read()
        }
        window.addComponentListener(listener)
        read()
        onDispose { window.removeComponentListener(listener) }
    }
    return insets
}

private fun macInsets(fullScreen: Boolean): PaddingValues {
    val top = if (fullScreen) 0.dp else MacTitleBarHeight
    return PaddingValues(top = top, start = if (top > 0.dp) MacTrafficLightsWidth else 0.dp)
}
