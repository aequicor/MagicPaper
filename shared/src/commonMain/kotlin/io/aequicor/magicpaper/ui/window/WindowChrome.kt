package io.aequicor.magicpaper.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Управление хромом окна (свернуть / развернуть / закрыть).
 * Не равно нулю на платформах без системных декораций — сейчас это десктоп
 * с его кастомным тайтлбаром. Остальные платформы получают системный хром от ОС.
 */
interface WindowChrome {
    fun minimize()
    fun toggleMaximize()
    fun close()
}

/** Текущий хром окна; `null` там, где декорации системные. */
val LocalWindowChrome = staticCompositionLocalOf<WindowChrome?> { null }

/**
 * Область перетаскивания окна: на десктопе за неё можно тянуть окно,
 * на остальных платформах — прозрачный проход. Клики по содержимому сохраняются:
 * перетаскивание стартует только при реальном движении мыши.
 */
@Composable
expect fun WindowDragArea(modifier: Modifier = Modifier, content: @Composable () -> Unit)
