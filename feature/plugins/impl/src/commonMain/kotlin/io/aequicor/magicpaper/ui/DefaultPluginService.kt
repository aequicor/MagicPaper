package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.storage.EventJournal
import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.domain.PluginState
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class DefaultPluginService(
    private val registry: PluginRegistry,
    store: KeyValueStore,
    journal: EventJournal,
    json: Json,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val storageDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PluginService {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val inputs = PluginInputJournal(store, journal, json)
    private val mutableState = MutableStateFlow(PluginsState(plugins = registry.all()))
    override val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var commands = Channel<Command>(Channel.UNLIMITED)
    private var worker: Job? = null
    private var closed = false
    private var resetting = false

    override suspend fun start() = mutex.withLock {
        check(!closed) { "Plugin owner is closed" }
        val previousReset = resetting
        resetting = true
        try {
            drain(allowStopped = true)
            withContext(storageDispatcher) { inputs.restore() }
            publish()
            registry.all().filterIsInstance<PersistentPlugin>().forEach { it.resumeAfterReset() }
            if (worker?.isActive != true) {
                commands = Channel(Channel.UNLIMITED)
                val accepted = commands
                worker = scope.launch {
                    try { for (command in accepted) when (command) {
                    is Command.Barrier -> command.done.complete(Unit)
                    is Command.Toggle -> {
                        try {
                            val input = if (registry.byId(command.id) == null) PluginMachine.Fact.NeighbourMissing(command.id)
                                else PluginMachine.Intent.Toggle(command.id, command.enabled)
                            withContext(storageDispatcher) { inputs.commit(input) }
                            publish()
                            AppLog.info("plugins", "toggle_completed", mapOf("entityId" to command.id,
                                "result" to if (registry.byId(command.id) == null) "neighbour_missing" else "saved"))
                        } catch (cancelled: CancellationException) {
                            if (inputs.state.persistenceUnknown) failed("toggle_cancelled", cancelled)
                            else { publish(); mutableState.value = mutableState.value.copy(error = "Запись завершена, обработчик остановлен. Повторно откройте приложение.") }
                            throw cancelled
                        } catch (rejected: PluginPreferenceRejected) { validationFailed(rejected) }
                        catch (failure: Exception) { failed("toggle", failure) }
                    }
                    } } finally {
                        accepted.close()
                    }
                }
            }
            resetting = false
            AppLog.info("plugins", "started", mapOf("count" to inputs.state.preferences.size.toString()))
        } catch (failure: Throwable) {
            resetting = previousReset
            if (failure !is CancellationException) failed("restore", failure)
            throw failure
        }
    }

    override fun togglePlugin(id: String, enabled: Boolean) {
        if (resetting || closed || !mutableState.value.loaded || inputs.state.persistenceUnknown || worker?.isActive != true) {
            AppLog.debug("plugins", "toggle_rejected", mapOf("entityId" to id, "reason" to "owner_unavailable"))
            return
        }
        if (commands.trySend(Command.Toggle(id, enabled)).isFailure)
            failed("enqueue", IllegalStateException("Plugin command queue closed"))
        else AppLog.info("plugins", "toggle_requested", mapOf("entityId" to id, "action" to if (enabled) "enable" else "disable"))
    }

    override suspend fun exportPreferences(): List<PluginState> {
        if (!mutableState.value.loaded) start()
        return mutex.withLock {
            check(!closed && !resetting) { "Plugin owner unavailable" }
            drain()
            check(!inputs.state.persistenceUnknown) { "Plugin persistence unresolved" }
            projectedPreferences().values.toList()
        }
    }

    override suspend fun importPreferences(preferences: List<PluginState>) = editPreferences("import", PluginMachine.Intent.Import(preferences.map { it.copy(config = it.config.toMap()) }))
    override suspend fun clearPreferences() = editPreferences("clear", PluginMachine.Intent.Clear, reload = true)

    private suspend fun editPreferences(operation: String, input: PluginMachine.Intent, reload: Boolean = false) {
        if (!mutableState.value.loaded) start()
        mutex.withLock {
            check(!closed && (!resetting || reload)) { "Plugin owner unavailable" }
            val previousReset = resetting
            resetting = true
            try {
                drain()
                withContext(storageDispatcher) {
                    // Application reset may have replaced the stream. Never append using its old epoch.
                    if (reload) inputs.restore()
                    inputs.commit(input)
                }
                publish()
                AppLog.info("plugins", operation + "_completed", mapOf("count" to inputs.state.preferences.size.toString()))
            } catch (cancelled: CancellationException) { publish(); throw cancelled }
            catch (rejected: PluginPreferenceRejected) { validationFailed(rejected); throw rejected }
            catch (failure: Exception) { failed(operation, failure); throw failure }
            finally { resetting = previousReset }
        }
    }

    private fun projectedPreferences(): Map<String, PluginState> {
        val preferences = inputs.state.preferences.mapValues { (_, value) -> value.copy(config = value.config.toMap()) }.toMutableMap()
        registry.all().forEach { if (it.id !in preferences) preferences[it.id] = PluginState(it.id) }
        return preferences
    }

    private fun publish() {
        val saved = inputs.state
        mutableState.value = PluginsState(registry.all(), projectedPreferences(), loaded = saved.initialized,
            error = when { saved.persistenceUnknown -> persistenceMessage
                saved.missingPlugin != null -> "Плагин недоступен. Обновите список плагинов."
                else -> null })
    }

    private fun validationFailed(failure: PluginPreferenceRejected) {
        publish()
        mutableState.value = mutableState.value.copy(error = failure.message)
    }

    private fun failed(operation: String, failure: Throwable) {
        inputs.markUnknown()
        AppLog.error("plugins", operation + "_failed", IllegalStateException("Plugin operation failed"),
            mapOf("causeType" to (failure::class.simpleName ?: "Failure"), "result" to "previous_state_retained"))
        publish()
    }

    private suspend fun drain(allowStopped: Boolean = false) {
        val active = worker ?: return
        if (!active.isActive) {
            check(allowStopped) { "Plugin writer stopped; reload required" }
            return
        }
        val done = CompletableDeferred<Unit>()
        check(commands.trySend(Command.Barrier(done)).isSuccess) { "Plugin command queue closed" }
        select<Unit> {
            done.onAwait { }
            active.onJoin {
                check(done.isCompleted) { "Plugin writer stopped before its barrier" }
                done.await()
            }
        }
    }

    override suspend fun prepareForReset() = mutex.withLock {
        resetting = true
        drain()
        registry.all().filterIsInstance<PersistentPlugin>().forEach { it.prepareForReset() }
    }
    override suspend fun removeProjectDrafts(projectId: String, planIds: Set<String>?) = mutex.withLock {
        registry.all().filterIsInstance<PersistentPlugin>().forEach { it.removeProjectDrafts(projectId, planIds) }
        AppLog.info("plugins", "drafts_removed", mapOf("projectId" to projectId,
            "count" to (planIds?.size ?: 0).toString(), "operation" to if (planIds == null) "project" else "plans"))
    }
    override suspend fun close() {
        var failure: Throwable? = null
        fun retain(next: Throwable) {
            val previous = failure
            if (previous == null) failure = next
            else if (previous !== next) {
                if (next is CancellationException && previous !is CancellationException) {
                    next.addSuppressed(previous); failure = next
                } else previous.addSuppressed(next)
            }
        }
        withContext(NonCancellable) {
            try {
                val pending = mutex.withLock { closed = true; commands.close(); worker }
                try { pending?.join() } catch (next: Throwable) { retain(next) }
                registry.all().filterIsInstance<PersistentPlugin>().forEach { plugin ->
                    try { plugin.flushDrafts() } catch (next: Throwable) { retain(next) }
                }
            } finally { scope.cancel() }
        }
        try { currentCoroutineContext().ensureActive() } catch (cancelled: CancellationException) { retain(cancelled) }
        failure?.let { throw it }
        AppLog.info("plugins", "closed")
    }

    private sealed interface Command {
        data class Toggle(val id: String, val enabled: Boolean) : Command
        data class Barrier(val done: CompletableDeferred<Unit>) : Command
    }
    private companion object {
        const val persistenceMessage = "Не удалось сохранить настройки плагинов. Повторно откройте приложение и проверьте настройки."
    }
}
