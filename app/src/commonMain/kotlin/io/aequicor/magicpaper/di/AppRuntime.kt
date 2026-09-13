package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.logging.AppLog

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication

data class NavigationSessionConfig(
    val journalKey: String = "main",
    val restoreFromKey: String? = null,
    val initialDeepLink: String? = null,
)

sealed interface RuntimeState {
    data object Loading : RuntimeState
    data object Ready : RuntimeState
    data class Failed(val message: String) : RuntimeState
    data object Closed : RuntimeState
}

/** One isolated Koin application per platform application, never the global Koin context. */
class MagicPaperRuntime internal constructor(
    val navigationSession: NavigationSessionConfig,
    private val onPlatformStarted: () -> Unit,
    private val onPlatformClosed: suspend () -> Unit,
    definitions: (CoroutineScope) -> Module,
) {
    private val _ready = MutableStateFlow<RuntimeState>(RuntimeState.Loading)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error ->
        AppLog.error("runtime", "background.failed", error)
        _ready.value = RuntimeState.Failed("Фоновая работа завершилась ошибкой. Перезапустите приложение для восстановления.")
    })
    private val application = koinApplication {
        allowOverride(false)
        modules(definitions(scope))
    }
    val koin: Koin get() = application.koin
    val ready = _ready.asStateFlow()
    private var starting: Job? = null
    private var closing: Job? = null
    private var closed = false
    private var settingsService: SettingsService? = null
    private var chatService: ChatService? = null
    private var codingService: CodingService? = null
    private var pluginService: PluginService? = null
    private var codingGraph: CodingRuntimeGraph? = null

    fun start() {
        if (starting != null || closed) return
        AppLog.info("runtime", "start")
        starting = scope.launch {
            try {
                // Migrate and hydrate saved credentials before any runtime can restore work.
                koin.get<SettingsRepository>().load()
                koin.get<LlmProfileRepository>().load()
                // Track graph ownership before dependent constructors can fail.
                codingGraph = koin.get()
                val settings = koin.get<SettingsService>().also { settingsService = it }
                val chat = koin.get<ChatService>().also { chatService = it }
                val coding = koin.get<CodingService>().also { codingService = it }
                settings.start()
                // Native skill adapters must exist before restoration can launch a coding run.
                koin.get<PluginService>().also { pluginService = it }.start()
                chat.start()
                coding.start()
                scope.launch {
                    settings.state.collect { state ->
                        chat.updateConfiguration(state.settings, state.llmProfiles, state.openAiSubscription.available, state.openAiSubscription.account?.signedIn == true)
                        coding.updateConfiguration(state.settings, state.llmProfiles, state.openAiSubscription.available, state.openAiSubscription.account?.signedIn == true)
                    }
                }
                codingGraph?.start()
                onPlatformStarted()
                _ready.value = RuntimeState.Ready
                AppLog.info("runtime", "ready")
            } catch (error: CancellationException) {
                throw error
            } catch (failure: Exception) {
                AppLog.error("runtime", "restore.failed", failure)
                // Backend errors may include credentials: expose a stable, non-secret message.
                _ready.value = RuntimeState.Failed("Не удалось восстановить данные приложения.")
            }
        }
    }

    private val _shutdownErrors = MutableStateFlow<List<String>>(emptyList())
    val shutdownErrors = _shutdownErrors.asStateFlow()

    fun close() {
        if (closed) return
        closed = true
        AppLog.info("runtime", "close.started")
        closing = scope.launch {
            withContext(NonCancellable) {
                var cancellation: CancellationException? = null
                suspend fun cleanup(owner: String, action: suspend () -> Unit) {
                    try { action() }
                    catch (cancelled: CancellationException) {
                        cancellation = cancellation ?: cancelled
                        AppLog.info("runtime", "close.owner.cancelled", mapOf("component" to owner))
                        _shutdownErrors.update { it + owner }
                    }
                    catch (failure: Exception) {
                        AppLog.error("runtime", "close.owner.failed", failure, mapOf("component" to owner))
                        _shutdownErrors.update { it + owner }
                    }
                }
                // Teardown owns failures and continues through every already-created owner.
                cleanup("startup") { starting?.cancelAndJoin() }
                cleanup("chat") { chatService?.close() }
                cleanup("coding") { codingService?.close() }
                cleanup("execution") { codingGraph?.close() }
                cleanup("settings") { settingsService?.close() }
                cleanup("plugins") { pluginService?.close() }
                cleanup("platform") { onPlatformClosed() }
                cleanup("koin") { application.close() }
                _ready.value = RuntimeState.Closed
                AppLog.info("runtime", "close.finished", mapOf("outcome" to if (_shutdownErrors.value.isEmpty()) "completed" else "cleanup-failed"))
                scope.cancel()
                cancellation?.let { throw it }
            }
        }
    }

    suspend fun awaitClosed() { closing?.join() }
}
