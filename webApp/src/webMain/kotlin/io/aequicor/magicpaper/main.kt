package io.aequicor.magicpaper

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.designsystem.PaperSurface
import io.aequicor.magicpaper.designsystem.PaperText
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.lifecycle.stop
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.di.MagicPaperRuntime
import io.aequicor.magicpaper.di.NavigationSessionConfig
import io.aequicor.magicpaper.di.createMagicPaperRuntime
import io.aequicor.magicpaper.navigation.BrowserHistoryBridge
import io.aequicor.magicpaper.navigation.WindowBrowserHistoryPort
import io.aequicor.magicpaper.navigation.browserNavigationSession
import io.aequicor.magicpaper.navigation.browserDocumentHidden
import io.aequicor.magicpaper.navigation.createAppRoot
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    scope.launch {
        AppLog.info("web_host", "starting")
        val session = try { browserNavigationSession() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            AppLog.error("web_host", "journal_start_failed", failure, mapOf("result" to "startup_blocked"))
            renderStartupFailure("Не удалось открыть историю окна. Обновите страницу.")
            return@launch
        }
        var initializedRuntime: MagicPaperRuntime? = null
        try {
        val runtime = createMagicPaperRuntime(NavigationSessionConfig(
            journalKey = session.journalKey,
            restoreFromKey = session.restoreFromKey,
            initialDeepLink = session.initialPath,
        ))
        initializedRuntime = runtime
        runtime.start()
        val lifecycle = LifecycleRegistry()
        val root = createAppRoot(runtime, DefaultComponentContext(lifecycle))
        BrowserHistoryBridge(root, WindowBrowserHistoryPort(), session.journalKey, scope,
            onError = root::reportNavigationError)
        // Hidden tabs stop component presentation; application jobs retain their own lifetime.
        fun updateLifecycle() { if (browserDocumentHidden()) lifecycle.stop() else lifecycle.resume() }
        document.addEventListener("visibilitychange", { updateLifecycle() })
        updateLifecycle()
        ComposeViewport { App(runtime, root) }
        AppLog.info("web_host", "mounted")
        } catch (cancelled: CancellationException) {
            initializedRuntime?.close()
            session.close()
            throw cancelled
        } catch (failure: Exception) {
            AppLog.error("web_host", "startup_failed", failure, mapOf("result" to "startup_blocked"))
            initializedRuntime?.close()
            session.close()
            renderStartupFailure("Не удалось открыть MagicPaper. Обновите страницу.")
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun renderStartupFailure(message: String) {
    ComposeViewport { PaperTheme { PaperSurface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PaperText(message) }
    } } }
}
