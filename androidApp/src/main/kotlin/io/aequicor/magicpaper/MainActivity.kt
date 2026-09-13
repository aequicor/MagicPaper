package io.aequicor.magicpaper

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.designsystem.PaperSurface
import io.aequicor.magicpaper.designsystem.PaperText
import kotlinx.coroutines.CancellationException
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.arkivanov.decompose.retainedComponent
import com.arkivanov.essenty.lifecycle.doOnDestroy
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.di.AndroidEnv
import io.aequicor.magicpaper.di.MagicPaperRuntime
import io.aequicor.magicpaper.di.NavigationSessionConfig
import io.aequicor.magicpaper.di.createMagicPaperRuntime
import io.aequicor.magicpaper.navigation.AppChild
import io.aequicor.magicpaper.navigation.RootComponent
import io.aequicor.magicpaper.navigation.createAppRoot

class MainActivity : ComponentActivity() {
    private lateinit var host: AndroidAppHost

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidEnv.context = applicationContext
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppLog.info("android_host", "created", mapOf("strategy" to if (savedInstanceState == null) "launch" else "restore"))
        // Both graph and components survive configuration changes; no Activity enters DI.
        host = try { retainedComponent { context ->
            val runtime = createMagicPaperRuntime(NavigationSessionConfig(
                initialDeepLink = if (savedInstanceState == null) intent.deepLink() else null,
            ))
            try {
                runtime.start()
                val root = createAppRoot(runtime, context)
                context.lifecycle.doOnDestroy(runtime::close)
                AndroidAppHost(runtime, root)
            } catch (failure: Exception) { runtime.close(); throw failure }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            AppLog.error("android_host", "startup_failed", failure, mapOf("result" to "startup_blocked"))
            setContent { PaperTheme { PaperSurface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PaperText("Не удалось открыть MagicPaper. Закройте приложение и повторите запуск.")
                }
            } } }
            return
        }
        setContent { App(host.runtime, host.root) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!::host.isInitialized) return
        intent.deepLink()?.let {
            AppLog.info("android_host", "activation_received")
            host.root.handleDeepLink(it)
        }
    }
}

private data class AndroidAppHost(val runtime: MagicPaperRuntime, val root: RootComponent<AppChild>)
private fun Intent.deepLink(): String? = dataString?.takeIf {
    action == Intent.ACTION_VIEW && it.startsWith("magicpaper://", ignoreCase = true)
}
