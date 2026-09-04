package io.aequicor.magicpaper.ui.window

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Управление хромом окна (свернуть / развернуть / закрыть).
 * Не равно нулю на платформах без системных декораций — сейчас это
 * десктопные Windows и Linux с их кастомным тайтлбаром.
 * На macOS декорации нативные (светофор, скругления, снап к краям),
 * но тайтлбар прозрачный и контент рисуется под ним.
 */
interface WindowChrome {
    fun minimize()
    fun toggleMaximize()
    fun close()
}

/** Текущий хром окна; `null` там, где декорации системные. */
val LocalWindowChrome = staticCompositionLocalOf<WindowChrome?> { null }

/**
 * Инсеты нативного тайтлбара, под который заезжает контент (edge-to-edge):
 * top — высота тайтлбара (macOS), start — зона «светофора», чтобы кнопки
 * приложения не оказывались под нативными кнопками окна.
 * На остальных платформах — пустые.
 */
val LocalWindowTitleBarInsets = staticCompositionLocalOf { PaddingValues() }

/**
 * Область перетаскивания окна: на десктопе за неё можно тянуть окно,
 * на остальных платформах — прозрачный проход. Клики по содержимому сохраняются:
 * перетаскивание стартует только при реальном движении мыши.
 */
@Composable
expect fun WindowDragArea(modifier: Modifier = Modifier, content: @Composable () -> Unit)
