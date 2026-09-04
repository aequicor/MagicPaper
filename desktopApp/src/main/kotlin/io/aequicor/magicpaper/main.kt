package io.aequicor.magicpaper

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Frame
import java.awt.Image
import javax.imageio.ImageIO

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

fun main() = application {
    val state = rememberWindowState(width = 1000.dp, height = 700.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = "MagicPaper — Шалость удалась",
        state = state,
    ) {
        setAppIcons()
        App()
    }
}
