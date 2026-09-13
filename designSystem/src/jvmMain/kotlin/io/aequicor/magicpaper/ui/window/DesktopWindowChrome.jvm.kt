package io.aequicor.magicpaper.ui.window

import java.awt.Frame
import java.awt.Window
import java.awt.event.WindowEvent

/** Хром окна на десктопе: управляем AWT-окном напрямую. */
class DesktopWindowChrome(private val window: Window) : WindowChrome {
    private val frame: Frame? get() = window as? Frame

    override fun minimize() {
        frame?.extendedState = Frame.ICONIFIED
    }

    override fun toggleMaximize() {
        val f = frame ?: return
        val maximized = Frame.MAXIMIZED_BOTH
        f.extendedState = if (f.extendedState and maximized == maximized) Frame.NORMAL else maximized
    }

    /**
     * Эмулируем системное событие закрытия: срабатывает штатная цепочка
     * `onCloseRequest` точки входа, а не грубый `dispose()`.
     */
    override fun close() {
        window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
    }
}
