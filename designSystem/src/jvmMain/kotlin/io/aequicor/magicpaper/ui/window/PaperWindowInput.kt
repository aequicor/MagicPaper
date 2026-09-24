package io.aequicor.magicpaper.ui.window

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.awt.Window

/** Native hit-test transparency: decorative windows must never intercept the desktop pointer. */
public fun paperWindowIgnoreMouse(window: Window, ignore: Boolean) {
    val os = System.getProperty("os.name")
    if (os.startsWith("Mac")) {
        onAppKitWindow(window) { appKit, nsWindow ->
            appKit.send.invokeVoid(arrayOf(nsWindow, appKit.selector("setIgnoresMouseEvents:"), if (ignore) 1.toByte() else 0.toByte()))
            val applied = appKit.send.invokeInt(arrayOf(nsWindow, appKit.selector("ignoresMouseEvents"))) and 0xff != 0
            check(applied == ignore) { "Native window did not accept hit-test transparency" }
        }
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

/**
 * macOS: a floating panel stays with the reader on every Space, including the Space of an
 * application in full screen, the way the system Picture in Picture window follows them. AWT
 * creates every window managed by a single Space and exposes no property for this.
 *
 * Other platforms have no Spaces; an always-on-top window already stays in front there, so the
 * call returns false without doing anything. Call it once the window is displayable; it throws
 * when AppKit refuses.
 */
public fun paperWindowFloatOnAllSpaces(window: Window): Boolean {
    if (!System.getProperty("os.name").startsWith("Mac")) return false
    onAppKitWindow(window) { appKit, nsWindow ->
        val current = appKit.send.invokeLong(arrayOf(nsWindow, appKit.selector("collectionBehavior")))
        // AppKit rejects mutually exclusive behaviours with an exception, so their rivals go first.
        val next = (current and SPACE_RIVALS.inv()) or SPACE_BEHAVIOUR
        appKit.send.invokeVoid(arrayOf(nsWindow, appKit.selector("setCollectionBehavior:"), next))
        val applied = appKit.send.invokeLong(arrayOf(nsWindow, appKit.selector("collectionBehavior")))
        check(applied and SPACE_BEHAVIOUR == SPACE_BEHAVIOUR) { "Native window did not accept Space behaviour" }
    }
    return true
}

// NSWindowCollectionBehavior bits.
private const val CAN_JOIN_ALL_SPACES = 1L shl 0
private const val MOVE_TO_ACTIVE_SPACE = 1L shl 1
private const val FULL_SCREEN_PRIMARY = 1L shl 7
private const val FULL_SCREEN_AUXILIARY = 1L shl 8
private const val FULL_SCREEN_NONE = 1L shl 9
private const val SPACE_BEHAVIOUR = CAN_JOIN_ALL_SPACES or FULL_SCREEN_AUXILIARY
private const val SPACE_RIVALS = MOVE_TO_ACTIVE_SPACE or FULL_SCREEN_PRIMARY or FULL_SCREEN_NONE

/** Objective-C messaging for one AppKit call sequence. */
private class AppKit(private val objc: NativeLibrary) {
    val send: Function = objc.getFunction("objc_msgSend")
    fun selector(name: String): Pointer = objc.getFunction("sel_registerName").invokePointer(arrayOf(name))
    fun type(name: String): Pointer = objc.getFunction("objc_getClass").invokePointer(arrayOf(name))
}

/**
 * Runs [action] on AppKit's main thread against the NSWindow behind [window], and rethrows its
 * failure on the caller. Modern JAWT returns a CALayer, not an NSView, so the window is resolved
 * as the single app-owned NSWindow with the same title instead of messaging the JAWT pointer.
 */
private fun onAppKitWindow(window: Window, action: (AppKit, Pointer) -> Unit) {
    val appKit = AppKit(NativeLibrary.getInstance("objc"))
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
                val send = appKit.send
                val app = send.invokePointer(arrayOf(appKit.type("NSApplication"), appKit.selector("sharedApplication")))
                val windows = send.invokePointer(arrayOf(app, appKit.selector("windows")))
                val count = send.invokeLong(arrayOf(windows, appKit.selector("count")))
                val matches = (0 until count).mapNotNull { index ->
                    val candidate = send.invokePointer(arrayOf(windows, appKit.selector("objectAtIndex:"), index))
                    val text = send.invokePointer(arrayOf(candidate, appKit.selector("title")))
                    val bytes = send.invokePointer(arrayOf(text, appKit.selector("UTF8String")))
                    candidate.takeIf { bytes?.getString(0, "UTF-8") == title }
                }
                check(matches.size == 1) { "Native window identity is unavailable or ambiguous" }
                action(appKit, matches.single())
            } catch (error: Throwable) { failure = error }
        }
    }
    dispatch.getFunction("dispatch_sync_f").invokeVoid(arrayOf(queue, null, callback))
    failure?.let { throw it }
}

private interface MainQueueCallback : Callback { fun invoke(context: Pointer?) }
