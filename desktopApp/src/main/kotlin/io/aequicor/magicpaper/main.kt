package io.aequicor.magicpaper

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.aequicor.magicpaper.ui.window.DesktopWindowChrome
import io.aequicor.magicpaper.ui.window.LocalWindowChrome
import io.aequicor.magicpaper.ui.window.LocalWindowScope
import io.aequicor.magicpaper.ui.window.LocalWindowTitleBarInsets
import io.aequicor.magicpaper.ui.window.LocalWindowToolbarHeight
import io.aequicor.magicpaper.ui.window.LocalWindowsTitleBarController
import io.aequicor.magicpaper.ui.window.WindowsTitleBarController
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.designsystem.PaperSurface
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperCommandMenu
import io.aequicor.magicpaper.designsystem.PaperColors
import io.aequicor.magicpaper.di.createMagicPaperRuntime
import io.aequicor.magicpaper.di.MagicPaperRuntime
import io.aequicor.magicpaper.navigation.AppChild
import io.aequicor.magicpaper.navigation.AppRoute
import io.aequicor.magicpaper.navigation.RootComponent
import io.aequicor.magicpaper.navigation.createAppRoot
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.lifecycle.destroy
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.desktop.AppReopenedListener
import java.util.concurrent.atomic.AtomicBoolean
import java.awt.Desktop
import java.nio.file.Path
import javax.swing.SwingUtilities
import java.awt.Frame
import java.awt.Image
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.imageio.ImageIO
import javax.swing.JFrame

/**
 * Иконки окна и дока: все разрешения, ОС выберет нужное.
 * Генерируются скриптом assets/icon/gen_icons.py.
 */
private val AppIcons: List<Image> by lazy {
    listOf(16, 32, 48, 128, 256).mapNotNull { size ->
        try { ImageIO.read(object {}.javaClass.getResourceAsStream("/icons/app_$size.png")) }
        catch (failure: Exception) {
            AppLog.error("desktop_host", "icon_load_failed", failure, mapOf("result" to "system_icon"))
            null
        }
    }
}

private fun FrameWindowScope.setAppIcons() {
    if (AppIcons.isNotEmpty()) (window as? Frame)?.iconImages = AppIcons
}

/** Нативная высота тайтлбара macOS (unified title bar). */
private val MacTitleBarHeight = 28.dp

/** Ширина зоны «светофора»: три кнопки по ~12pt с шагом 20, отступ 7pt + запас. */
private val MacTrafficLightsWidth = 78.dp

fun main(args: Array<String>) {
    AppLog.info("desktop_host", "starting")
    try { runDesktopHost(args) }
    catch (failure: Exception) {
        AppLog.error("desktop_host", "startup_failed", failure, mapOf("result" to "startup_blocked"))
        application(exitProcessOnExit = false) {
            Window(onCloseRequest = ::exitApplication, title = "MagicPaper") {
                PaperTheme { PaperSurface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        PaperText("Не удалось открыть MagicPaper. Закройте приложение и повторите запуск.")
                    }
                } }
            }
        }
    }
}

private fun runDesktopHost(args: Array<String>) {
    val activations = Channel<List<String>>(Channel.UNLIMITED)
    fun enqueueActivation(links: List<String>) {
        check(activations.trySend(links).isSuccess) { "Desktop activation owner closed" }
    }
    if (Desktop.isDesktopSupported()) {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
            desktop.addAppEventListener(AppReopenedListener {
                try { enqueueActivation(emptyList()) } catch (failure: Exception) { AppLog.error("desktop_host", "reopen_failed", failure) }
            })
        }
        if (desktop.isSupported(Desktop.Action.APP_OPEN_URI)) {
            desktop.setOpenURIHandler { event ->
                try { enqueueActivation(listOf(event.uri.toString())) } catch (failure: Exception) { AppLog.error("desktop_host", "activation_failed", failure) }
            }
        }
    }
    val acquisition = DesktopActivationBroker.acquire(
        directory = Path.of(System.getProperty("user.home"), ".MagicPaper", "activation"),
        initialLinks = args.filter { it.startsWith("magicpaper://", ignoreCase = true) },
        onActivation = ::enqueueActivation,
    )
    if (acquisition == DesktopActivationBroker.Result.Forwarded) return
    val broker = (acquisition as DesktopActivationBroker.Result.Primary).broker
    lateinit var runtime: MagicPaperRuntime
    lateinit var root: RootComponent<AppChild>
    var initializedRuntime: MagicPaperRuntime? = null
    var initializedRoot: RootComponent<AppChild>? = null
    val lifecycle = LifecycleRegistry()
    val closing = AtomicBoolean()
    fun closeHost() {
        if (!closing.compareAndSet(false, true)) return
        AppLog.info("desktop_host", "closing")
        fun cleanup(phase: String, action: () -> Unit) {
            try { action() } catch (failure: Exception) { AppLog.error("desktop_host", "cleanup_failed", failure, mapOf("phase" to phase)) }
        }
        // Every cleanup phase runs even when an earlier drain or resource close failed.
        cleanup("navigation_drain") {
            check(runBlocking { withTimeoutOrNull(10_000) { initializedRoot?.awaitIdle(); true } } == true) { "Navigation drain timed out" }
        }
        cleanup("component_close") { SwingUtilities.invokeAndWait { lifecycle.destroy() } }
        cleanup("runtime_close") { SwingUtilities.invokeAndWait { initializedRuntime?.close() } }
        cleanup("runtime_drain") {
            check(runBlocking { withTimeoutOrNull(10_000) { initializedRuntime?.awaitClosed(); true } } == true) { "Runtime drain timed out" }
        }
        activations.close()
        cleanup("activation_close") { broker.close() }
        AppLog.info("desktop_host", "closed")
    }
    val shutdownHook = Thread(::closeHost, "magicpaper-shutdown")
    Runtime.getRuntime().addShutdownHook(shutdownHook)
    try {
        SwingUtilities.invokeAndWait {
            runtime = createMagicPaperRuntime().also { initializedRuntime = it }
            runtime.start()
            root = createAppRoot(runtime, DefaultComponentContext(lifecycle)).also { initializedRoot = it }
            lifecycle.resume()
        }
        AppLog.info("desktop_host", "mounted")
        runMagicPaperWindow(runtime, root, activations)
    } finally {
        closeHost()
        try { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        catch (_: IllegalStateException) { AppLog.debug("desktop_host", "shutdown_hook_retained", mapOf("reason" to "jvm_shutting_down")) }
        catch (failure: Exception) { AppLog.error("desktop_host", "shutdown_hook_remove_failed", failure) }
    }
}

private fun runMagicPaperWindow(
    runtime: MagicPaperRuntime,
    root: RootComponent<AppChild>,
    activations: Channel<List<String>>,
) {
    val osName = System.getProperty("os.name")
    val isWindows = osName.lowercase().startsWith("windows")
    val mode = desktopWindowMode(
        osName = osName,
        windowDecorationsSupported = isWindows && WindowsTitleBarController.isSupported(),
    )
    application(exitProcessOnExit = false) {
        val state = rememberWindowState(width = 1000.dp, height = 700.dp)
        Window(
            onCloseRequest = ::exitApplication,
            title = "MagicPaper — Шалость удалась",
            state = state,
            // macOS: native transparent titlebar (edge-to-edge), system decorations.
            // Windows + JBR: decorated frame, Compose bar merged via JBR WindowDecorations
            //   — preserves border, shadow, Aero Snap and native min/max/close buttons.
            // Windows fallback: decorated frame, standard system title bar.
            // Linux: fully undecorated — Compose draws the entire chrome.
            undecorated = mode == DesktopWindowMode.LINUX_CUSTOM,
        ) {
            LaunchedEffect(root, window) {
                for (links in activations) {
                    AppLog.info("desktop_host", "activation_received", mapOf("count" to links.size.toString()))
                    try {
                    state.isMinimized = false
                    window.isVisible = true
                    window.toFront()
                    window.requestFocus()
                    if (Desktop.isDesktopSupported()) {
                        val desktop = Desktop.getDesktop()
                        if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) desktop.requestForeground(true)
                    }
                    } catch (failure: Exception) {
                        AppLog.error("desktop_host", "foreground_failed", failure, mapOf("result" to "navigation_continues"))
                        root.reportNavigationError("Не удалось вывести окно на передний план.")
                    }
                    links.forEach(root::handleDeepLink)
                }
            }
            PaperCommandMenu(onSettings = { root.navigate(AppRoute.Settings()) }, onClose = ::exitApplication)
            setAppIcons()
            val chrome = remember(window, mode) {
                if (mode == DesktopWindowMode.LINUX_CUSTOM) DesktopWindowChrome(window) else null
            }
            val windowsTitleBar = remember(window, mode) {
                if (mode == DesktopWindowMode.WINDOWS_JBR_CUSTOM) {
                    (window as? Frame)?.let(WindowsTitleBarController::create)
                } else {
                    null
                }
            }
            DisposableEffect(windowsTitleBar) {
                onDispose { windowsTitleBar?.dispose() }
            }
            val titleBarInsets = when (mode) {
                DesktopWindowMode.MAC_SYSTEM -> rememberMacTitleBarInsets()
                DesktopWindowMode.WINDOWS_JBR_CUSTOM -> PaddingValues(
                    start = (windowsTitleBar?.leftInset ?: 0f).dp,
                    end = (windowsTitleBar?.rightInset ?: 0f).dp,
                )
                else -> PaddingValues()
            }
            // Фон окна в цвет приложения — без белой вспышки в углах при ресайзе.
            val canvas = PaperColors().canvas
            window.background = java.awt.Color(canvas.red, canvas.green, canvas.blue)
            CompositionLocalProvider(
                LocalWindowChrome provides chrome,
                LocalWindowScope provides this,
                LocalWindowTitleBarInsets provides titleBarInsets,
                LocalWindowsTitleBarController provides windowsTitleBar,
                // macOS: одна строка с нативным «светофором», без второго ряда ниже.
                LocalWindowToolbarHeight provides if (mode == DesktopWindowMode.MAC_SYSTEM) MacTitleBarHeight else 40.dp,
            ) {
                App(runtime, root)
            }
        }
    }
}

/**
 * macOS: прозрачный нативный тайтлбар — контент рисуется под ним (edge-to-edge),
 * при этом «светофор», скругления углов и снап к краям экрана остаются нативными.
 * Возвращает инсеты: top — высота тайтлбара (0 в полноэкранном режиме),
 * start — зона «светофора», чтобы под ним не оказалось интерактивных элементов.
 */
@Composable
private fun FrameWindowScope.rememberMacTitleBarInsets(): PaddingValues {
    var insets by remember { mutableStateOf(macInsets(fullScreen = false)) }
    fun read() {
        // В полноэкранном режиме окно занимает ровно весь экран (с точностью
        // до пикселя), меню и тайтлбар прячутся — инсеты обнуляем.
        val bounds = window.bounds
        val screen = window.graphicsConfiguration?.bounds
        val fullScreen = screen != null &&
            bounds.width == screen.width && bounds.height == screen.height
        insets = macInsets(fullScreen)
    }
    DisposableEffect(window) {
        val rootPane = (window as? JFrame)?.rootPane
        fun applyTitleBarStyle() {
            // JBR-свойства: контент на всю высоту окна, тайтлбар прозрачный,
            // текст заголовка скрыт (заголовок рисует сам интерфейс).
            rootPane?.putClientProperty("apple.awt.fullWindowContent", true)
            rootPane?.putClientProperty("apple.awt.transparentTitleBar", true)
            rootPane?.putClientProperty("apple.awt.windowTitleVisible", false)
            rootPane?.revalidate()
            rootPane?.repaint()
        }
        applyTitleBarStyle()
        val listener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                applyTitleBarStyle()
                read()
            }

            override fun componentMoved(e: ComponentEvent) = read()
        }
        window.addComponentListener(listener)
        read()
        onDispose { window.removeComponentListener(listener) }
    }
    return insets
}

private fun macInsets(fullScreen: Boolean): PaddingValues {
    val top = if (fullScreen) 0.dp else MacTitleBarHeight
    return PaddingValues(top = top, start = if (top > 0.dp) MacTrafficLightsWidth else 0.dp)
}
