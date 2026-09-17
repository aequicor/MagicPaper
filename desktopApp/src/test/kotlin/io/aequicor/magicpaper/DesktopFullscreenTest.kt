package io.aequicor.magicpaper

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.ui.window.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Actual macOS window transitions, no Koin, storage, network, sessions or user data. */
class DesktopFullscreenTest {
    @Test fun nativeFullscreenUpdatesComposeStateAndRemovesThenRestoresAllChrome() = runBlocking {
        assumeTrue(System.getProperty("magicpaper.window.native") == "true")
        assumeTrue(System.getProperty("os.name").startsWith("Mac"))
        var failure: Throwable? = null
        val evidence = mutableListOf<String>()
        withTimeout(45_000) {
            awaitApplication {
                val state = rememberWindowState(width = 640.dp, height = 400.dp)
                var measuredHeight by remember { mutableStateOf(-1) }
                Window(onCloseRequest = ::exitApplication, state = state, title = "MagicPaper · Title bar check") {
                    val insets = rememberMacTitleBarInsets(state.placement == WindowPlacement.Fullscreen)
                    val currentInsets by rememberUpdatedState(insets)
                    CompositionLocalProvider(LocalWindowScope provides this, LocalWindowTitleBarInsets provides insets,
                        LocalWindowToolbarHeight provides desktopToolbarHeight(DesktopWindowMode.MAC_SYSTEM,
                            state.placement, LocalDensity.current.fontScale)) { PaperTheme {
                        PaperSurface(Modifier.fillMaxSize()) {
                            Column {
                                Box(Modifier.fillMaxWidth().onSizeChanged { measuredHeight = it.height }) {
                                    PaperAppTitleBar(true, {}) {
                                        PaperIconButton("Расходы", {}) { PaperText("$") }
                                        PaperIconButton("Настройки", {}) { PaperText("⚙") }
                                    }
                                }
                                PaperText("Проверка полноэкранного режима", Modifier.padding(16.dp))
                            }
                        }
                    } }
                    LaunchedEffect(window) {
                        var removeListener: (() -> Unit)? = null
                        try {
                        val entered = CompletableDeferred<Unit>()
                        val exited = CompletableDeferred<Unit>()
                        val listenerType = Class.forName("com.apple.eawt.FullScreenListener")
                        val utilities = Class.forName("com.apple.eawt.FullScreenUtilities")
                        val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
                            when (method.name) {
                                "windowEnteredFullScreen" -> { entered.complete(Unit); null }
                                "windowExitedFullScreen" -> { exited.complete(Unit); null }
                                "hashCode" -> System.identityHashCode(proxy)
                                "equals" -> proxy === args?.firstOrNull()
                                "toString" -> "Fullscreen test listener"
                                else -> null
                            }
                        }
                        utilities.getMethod("addFullScreenListenerTo", java.awt.Window::class.java, listenerType).invoke(null, window, listener)
                        removeListener = { utilities.getMethod("removeFullScreenListenerFrom", java.awt.Window::class.java, listenerType).invoke(null, window, listener); Unit }
                            withTimeout(10_000) { snapshotFlow { measuredHeight }.first { it > 0 } }
                            val initialHeight = measuredHeight
                            evidence += "windowed: ${state.placement}, toolbarPx=$measuredHeight"
                            // Change the native window, not Compose state; the host must observe the OS callback.
                            evidence += "request fullscreen"
                            window.placement = WindowPlacement.Fullscreen
                            withTimeout(15_000) { snapshotFlow { state.placement }.first { it == WindowPlacement.Fullscreen } }
                            withTimeout(10_000) { snapshotFlow { measuredHeight }.first { it == 0 } }
                            assertEquals(WindowPlacement.Fullscreen, window.placement)
                            assertTrue(currentInsets.calculateTopPadding() == 0.dp)
                            // macOS ignores a reverse request during the entry animation. Wait for
                            // its completion event instead of guessing an animation duration.
                            withTimeout(15_000) { entered.await() }
                            evidence += "fullscreen: ${state.placement}, toolbarPx=$measuredHeight, native transition complete"
                            evidence += "request restore"
                            window.placement = WindowPlacement.Floating
                            withTimeout(15_000) { snapshotFlow { state.placement }.first { it == WindowPlacement.Floating } }
                            withTimeout(10_000) { snapshotFlow { measuredHeight }.first { it == initialHeight } }
                            withTimeout(15_000) { exited.await() }
                            evidence += "restored: ${state.placement}, toolbarPx=$measuredHeight, native transition complete"
                        } catch (caught: Throwable) {
                            evidence += "failed: state=${state.placement}, native=${window.placement}, toolbarPx=$measuredHeight, bounds=${window.bounds}"
                            failure = caught
                        } finally {
                            try { removeListener?.invoke() } catch (cleanup: Throwable) {
                                if (failure == null) failure = cleanup else failure!!.addSuppressed(cleanup)
                            }
                            File("build/reports/titlebar/native-macos.txt").apply { parentFile.mkdirs() }.writeText(evidence.joinToString("\n"))
                            exitApplication()
                        }
                    }
                }
            }
        }
        failure?.let { throw it }
        File("build/reports/titlebar/native-macos.txt").apply { parentFile.mkdirs() }.writeText(evidence.joinToString("\n"))
    }
}
