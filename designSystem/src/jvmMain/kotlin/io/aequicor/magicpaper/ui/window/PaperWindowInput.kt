package io.aequicor.magicpaper.ui.window

import com.sun.jna.Callback
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.awt.Window

/** Native hit-test transparency: decorative windows must never intercept the desktop pointer. */
public fun paperWindowIgnoreMouse(window: Window, ignore: Boolean) {
    val os = System.getProperty("os.name")
    if (os.startsWith("Mac")) {
        val objc = NativeLibrary.getInstance("objc")
        val send = objc.getFunction("objc_msgSend")
        fun selector(name: String) = objc.getFunction("sel_registerName").invokePointer(arrayOf(name))
        // Modern JAWT returns a CALayer, not an NSView. Resolve an unambiguous app-owned
        // NSWindow on AppKit's thread instead of messaging the JAWT pointer as an NSView.
        val title = when (window) {
            is java.awt.Frame -> window.title
            is java.awt.Dialog -> window.title
            else -> error("Native window has no stable title")
        }
        var failure: Throwable? = null
        val dispatch = NativeLibrary.getInstance("System")
        val queue = dispatch.getGlobalVariableAddress("_dispatch_main_q")
        val callback = object : MainQueueCallback {
            override fun invoke(context: Pointer?) {
                try {
                    val type = objc.getFunction("objc_getClass").invokePointer(arrayOf("NSApplication"))
                    val app = send.invokePointer(arrayOf(type, selector("sharedApplication")))
                    val windows = send.invokePointer(arrayOf(app, selector("windows")))
                    val count = send.invokeLong(arrayOf(windows, selector("count")))
                    val matches = (0 until count).mapNotNull { index ->
                        val candidate = send.invokePointer(arrayOf(windows, selector("objectAtIndex:"), index))
                        val text = send.invokePointer(arrayOf(candidate, selector("title")))
                        val bytes = send.invokePointer(arrayOf(text, selector("UTF8String")))
                        candidate.takeIf { bytes?.getString(0, "UTF-8") == title }
                    }
                    check(matches.size == 1) { "Native window identity is unavailable or ambiguous" }
                    send.invokeVoid(arrayOf(matches.single(), selector("setIgnoresMouseEvents:"), if (ignore) 1.toByte() else 0.toByte()))
                    val applied = send.invokeInt(arrayOf(matches.single(), selector("ignoresMouseEvents"))) and 0xff != 0
                    check(applied == ignore) { "Native window did not accept hit-test transparency" }
                } catch (error: Throwable) { failure = error }
            }
        }
        dispatch.getFunction("dispatch_sync_f").invokeVoid(arrayOf(queue, null, callback))
        failure?.let { throw it }
    } else if (os.startsWith("Windows")) {
        val user = NativeLibrary.getInstance("user32")
        val hwnd = Native.getWindowPointer(window)
        Native.setLastError(0)
        val style = user.getFunction("GetWindowLongW").invokeInt(arrayOf(hwnd, -20))
        check(style != 0 || Native.getLastError() == 0) { "Cannot read overlay input configuration" }
        val root = (window as? javax.swing.RootPaneContainer)?.rootPane
            ?: error("Window does not expose a restoration owner")
        val key = "Paper.nativeMousePassthrough.style"
        // Layered windows use WS_EX_TRANSPARENT for cross-process hit-test transparency.
        val original = root.getClientProperty(key) as? Int ?: style
        val next = if (ignore) style or 0x80000 or 0x20 else original
        Native.setLastError(0)
        val previous = user.getFunction("SetWindowLongW").invokeInt(arrayOf(hwnd, -20, next))
        check(previous != 0 || Native.getLastError() == 0) { "Cannot configure overlay input" }
        if (ignore) {
            root.putClientProperty(key, original)
            if (style and 0x80000 == 0 && user.getFunction("SetLayeredWindowAttributes").invokeInt(arrayOf(hwnd, 0, 255.toByte(), 2)) == 0) {
                val failure = IllegalStateException("Cannot configure layered window input")
                Native.setLastError(0)
                val restored = user.getFunction("SetWindowLongW").invokeInt(arrayOf(hwnd, -20, original))
                if (restored == 0 && Native.getLastError() != 0) {
                    // Keep the saved style for a later cleanup attempt; do not report successful rollback.
                    failure.addSuppressed(IllegalStateException("Cannot restore overlay input configuration"))
                } else root.putClientProperty(key, null)
                throw failure
            }
        } else root.putClientProperty(key, null)
    } else error("Decorative desktop overlays are supported on macOS and Windows")
}

private interface MainQueueCallback : Callback { fun invoke(context: Pointer?) }
