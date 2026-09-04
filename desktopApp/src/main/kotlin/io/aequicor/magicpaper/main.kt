package io.aequicor.magicpaper

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "MagicPaper",
    ) {
        App()
    }
}