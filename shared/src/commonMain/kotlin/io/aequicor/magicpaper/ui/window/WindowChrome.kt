package io.aequicor.magicpaper.ui.window

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Управление хромом окна (свернуть / развернуть / закрыть).
 * Не равно нулю на платформах без системных кнопок — сейчас это Linux.
 * На Windows и macOS кнопки и рамку предоставляет оконная система.
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
 * top — высота системного тайтлбара над контентом (macOS), start/end — зоны
 * нативных кнопок, чтобы элементы приложения не оказывались под ними.
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

/**
 * Верхняя область приложения, объединённая с системным тайтлбаром там, где
 * платформа это поддерживает. Интерактивное содержимое сохраняет свои клики,
 * свободное место получает нативные drag и double-click-to-maximize.
 */
@Composable
expect fun WindowTitleBarArea(modifier: Modifier = Modifier, content: @Composable () -> Unit)
