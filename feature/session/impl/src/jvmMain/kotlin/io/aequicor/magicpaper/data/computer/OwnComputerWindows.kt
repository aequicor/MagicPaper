package io.aequicor.magicpaper.data.computer

import java.awt.EventQueue
import java.awt.Window
import java.util.concurrent.FutureTask
import io.aequicor.magicpaper.ui.window.paperWindowIgnoreMouse
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Memory
import com.sun.jna.ptr.IntByReference
import io.aequicor.magicpaper.logging.AppLog

/** Restore every changed window and preserve the primary operation failure. */
private fun <T> restoringWindows(restore: () -> Unit, operation: () -> T): T {
    var primary: Throwable? = null
    try { return operation() }
    catch (failure: Throwable) { primary = failure; throw failure }
    finally {
        try { restore() }
        catch (failure: Throwable) {
            AppLog.error("computer", "window.restore.failed", fields = mapOf("causeType" to failure.javaClass.simpleName))
            if (primary != null) primary.addSuppressed(failure) else throw failure
        }
    }
}

private fun <T> Iterable<T>.restoreEach(block: (T) -> Unit) {
    var failure: Throwable? = null
    for (item in this) try { block(item) } catch (error: Throwable) {
        if (failure == null) failure = error else failure.addSuppressed(error)
    }
    failure?.let { throw it }
}

private fun <T> onWindowThread(block: () -> T): T {
    if (EventQueue.isDispatchThread()) return block()
    val task = FutureTask(block)
    EventQueue.invokeAndWait(task)
    return task.get()
}

/** Input must reach the same underlying desktop that the agent saw, not its excluded chat window.
 * Also serves as the capture fallback on platforms without native per-application exclusion.
 * Restoration does not activate the app, and never resurrects a disposed window. */
internal fun <T> withoutOwnWindows(block: () -> T): T {
    val visible = mutableListOf<Window>()
    return restoringWindows(restore = {
        onWindowThread {
            visible.filter { it.isDisplayable }.restoreEach { window ->
                val autoFocus = window.isAutoRequestFocus
                window.isAutoRequestFocus = false
                try { window.isVisible = true } finally { window.isAutoRequestFocus = autoFocus }
            }
        }
    }) {
        onWindowThread { Window.getWindows().filter { it.isShowing }.forEach { window ->
            visible += window; window.isVisible = false
        } }
        if (visible.isNotEmpty()) Thread.sleep(80) // Let the compositor expose the underlying windows.
        block()
    }
}

internal fun <T> withOwnWindowsIgnoringMouse(inputBounds: java.awt.Rectangle?, block: () -> T): T {
    if (inputBounds == null) return block()
    val os = System.getProperty("os.name")
    if (!os.startsWith("Mac") && !os.startsWith("Windows")) return withoutOwnWindows(block)
    val windows = onWindowThread { Window.getWindows().filter {
        it.isShowing && it.name != "MagicPaperComputerOverlay" && it.bounds.intersects(inputBounds)
    } }
    val changed = mutableListOf<Window>()
    return restoringWindows(restore = {
        onWindowThread { changed.filter { it.isDisplayable }.restoreEach { paperWindowIgnoreMouse(it, false) } }
    }) {
        // Track before mutation so even a partially failed native setup gets a cleanup attempt.
        onWindowThread { windows.forEach { window -> changed += window; paperWindowIgnoreMouse(window, true) } }
        block()
    }
}

internal fun <T> withOwnWindowsExcludedFromCapture(block: () -> T): T {
    if (!System.getProperty("os.name").startsWith("Windows")) return withoutOwnWindows(block)
    // Older Windows would produce black rectangles instead of revealing underlying windows.
    val supported = Memory(284).use { version ->
        version.clear(); version.setInt(0, 284)
        val status = NativeLibrary.getInstance("ntdll").getFunction("RtlGetVersion").invokeInt(arrayOf(version))
        if (status != 0) AppLog.error("computer", "capture.version.failed", fields = mapOf("status" to status.toString(), "recovery" to "hide_own_windows"))
        status == 0 && version.getInt(12) >= 19041
    }
    if (!supported) return withoutOwnWindows(block)
    val user = NativeLibrary.getInstance("user32")
    val saved = mutableListOf<Pair<Window, Int>>()
    return captureWithWindowExclusion(restore = {
        onWindowThread { saved.filter { it.first.isDisplayable }.restoreEach { (window, affinity) ->
            check(user.getFunction("SetWindowDisplayAffinity").invokeInt(arrayOf(Native.getWindowPointer(window), affinity)) != 0) {
                "Не удалось восстановить режим захвата окна"
            }
        } }
    }, prepare = {
        onWindowThread {
            Window.getWindows().filter { it.isShowing }.forEach { window ->
                val hwnd = Native.getWindowPointer(window)
                val prior = IntByReference()
                // GetWindowDisplayAffinity is not guaranteed for non-layered windows.
                // Never guess its previous value: hide temporarily if it cannot be saved.
                if (user.getFunction("GetWindowDisplayAffinity").invokeInt(arrayOf(hwnd, prior)) == 0)
                    throw WindowCaptureExclusionUnavailable("read_affinity", Native.getLastError())
                if (user.getFunction("SetWindowDisplayAffinity").invokeInt(arrayOf(hwnd, 0x11)) == 0)
                    throw WindowCaptureExclusionUnavailable("set_affinity", Native.getLastError())
                saved += window to prior.value
            }
        }
        val status = NativeLibrary.getInstance("dwmapi").getFunction("DwmFlush").invokeInt(emptyArray())
        if (status != 0) throw WindowCaptureExclusionUnavailable("flush", status)
    }, capture = block, hiddenCapture = { withoutOwnWindows(block) })
}

internal class WindowCaptureExclusionUnavailable(val stage: String, val status: Int) :
    IllegalStateException("Не удалось подготовить исключение окна из снимка")

/** Retry only preparation, never a capture or an input action. Cleanup must finish before fallback. */
internal fun <T> captureWithWindowExclusion(
    prepare: () -> Unit,
    restore: () -> Unit,
    capture: () -> T,
    hiddenCapture: () -> T,
): T {
    try {
        prepare()
    } catch (failure: Throwable) {
        // The EDT FutureTask wraps native failures. Keep cancellation and unrelated errors intact.
        val cause = if (failure is java.util.concurrent.ExecutionException) failure.cause ?: failure else failure
        try { restore() } catch (cleanup: Throwable) {
            AppLog.error("computer", "window.restore.failed", fields = mapOf("causeType" to cleanup.javaClass.simpleName))
            cause.addSuppressed(cleanup)
            throw cause
        }
        if (cause !is WindowCaptureExclusionUnavailable) throw cause
        AppLog.error("computer", "capture.exclusion.failed", fields = mapOf(
            "stage" to cause.stage, "status" to cause.status.toString(), "recovery" to "hide_own_windows",
        ))
        return hiddenCapture()
    }
    return restoringWindows(restore, capture)
}
