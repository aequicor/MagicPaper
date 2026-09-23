package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.logging.phase

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import kotlin.time.TimeSource

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
    private var pluginService: PluginService? = null
    private var extensions: List<RuntimeExtension> = emptyList()
    private var mediaService: MediaGenerationService? = null

    fun start() {
        if (starting != null || closed) return
        AppLog.info("runtime", "start")
        starting = scope.launch {
            val started = TimeSource.Monotonic.markNow()
            // Each owner replays its whole saved history here. How long each one takes depends on
            // the machine's storage far more than on its CPU, so every phase reports its duration.
            suspend fun <T> phase(name: String, block: suspend () -> T): T = AppLog.phase("runtime", name) { block() }
            try {
                // Migrate and hydrate saved credentials before any runtime can restore work.
                phase("settings") {
                    koin.get<SettingsRepository>().load()
                    koin.get<LlmProfileRepository>().load()
                }
                phase("usage") { koin.get<UsageLedger>().start() }
                phase("skills") { koin.get<SkillCommands>().start() }
                val media = koin.get<MediaGenerationService>().also { mediaService = it }
                phase("media") { media.refreshAvailability() }
                // Track assembly ownership before dependent constructors can fail.
                extensions = phase("assembly") { koin.get<RuntimeExtensions>().owners }
                // Each feature replays its own journal while the owners below restore theirs: the replay needs only the
                // settings hydrated above and launches nothing, and an owner that reads the feature meanwhile waits for
                // it. A failure on either side cancels the other and fails the start as one.
                val (settings, chat) = coroutineScope {
                    val restores = extensions.map { extension -> async { phase("extension.${extension.id}.restore") { extension.restore() } } }
                    phase("media.recovery") { media.recoverPending() }
                    val settings = koin.get<SettingsService>().also { settingsService = it }
                    val chat = koin.get<ChatService>().also { chatService = it }
                    phase("settings.service") { settings.start() }
                    // Restored sessions need their skill adapters; restoration itself never launches a run.
                    phase("plugins") { koin.get<PluginService>().also { pluginService = it }.start() }
                    phase("chat") { chat.start() }
                    extensions.zip(restores).forEach { (extension, restore) ->
                        restore.await()
                        phase("extension.${extension.id}") { extension.start() }
                    }
                    settings to chat
                }
                scope.launch {
                    settings.state.collect { state ->
                        chat.updateConfiguration(state.settings, state.llmProfiles, state.openAiSubscription.available, state.openAiSubscription.account?.signedIn == true)
                        extensions.forEach { it.updateConfiguration(state) }
                    }
                }
                onPlatformStarted()
                _ready.value = RuntimeState.Ready
                AppLog.info("runtime", "ready", mapOf("elapsedMs" to started.elapsedNow().inWholeMilliseconds.toString()))
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
                extensions.asReversed().forEach { extension -> cleanup(extension.id) { extension.close() } }
                cleanup("media") { mediaService?.close() }
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
