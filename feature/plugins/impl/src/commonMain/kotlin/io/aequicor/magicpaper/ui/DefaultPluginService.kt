package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.domain.PluginState
import io.aequicor.magicpaper.domain.SettingsRepository
import io.aequicor.magicpaper.plugins.PluginRegistry
import io.aequicor.magicpaper.plugins.PersistentPlugin
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DefaultPluginService(
    private val registry: PluginRegistry,
    private val settingsRepository: SettingsRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : PluginService {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(PluginsState(plugins = registry.all()))
    override val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var worker: Job? = null
    private var closed = false
    private var resetting = false

    override suspend fun start() = mutex.withLock {
        if (closed) return@withLock
        val previouslyResetting = resetting
        resetting = true
        try {
            if (worker != null) {
                val done = CompletableDeferred<Unit>()
                commands.send(Command.Barrier(done))
                done.await()
            }
            // Retain unknown IDs and their private config for temporarily unavailable plugins.
            val stored = settingsRepository.pluginStates().associateByTo(linkedMapOf()) { it.id }
            registry.all().forEach { plugin -> if (plugin.id !in stored) stored[plugin.id] = PluginState(plugin.id) }
            mutableState.value = PluginsState(registry.all(), stored, loaded = true)
            registry.all().filterIsInstance<PersistentPlugin>().forEach { it.resumeAfterReset() }
            resetting = false
            AppLog.info("plugins", "started", mapOf("count" to stored.size.toString()))
            if (worker == null) worker = scope.launch {
                for (command in commands) {
                    if (command is Command.Barrier) { command.done.complete(Unit); continue }
                    command as Command.Toggle
                    val (id, enabled) = command
                    if (registry.byId(id) == null) continue
                    val current = mutableState.value
                    val previous = current.pluginStates[id] ?: PluginState(id)
                    if (previous.enabled == enabled) continue
                    val next = current.pluginStates + (id to previous.copy(enabled = enabled))
                    try {
                        settingsRepository.savePluginStates(next.values.toList())
                        mutableState.value = current.copy(pluginStates = next, error = null)
                        AppLog.info("plugins", "toggle_saved", mapOf("entityId" to id, "status" to if (enabled) "enabled" else "disabled"))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        AppLog.error("plugins", "toggle_failed", failure, mapOf("entityId" to id, "result" to "previous_state_retained"))
                        mutableState.value = current.copy(error = "Не удалось сохранить настройки плагина.")
                    }
                }
            }
        } catch (failure: Throwable) {
            resetting = previouslyResetting
            throw failure
        }
    }

    override fun togglePlugin(id: String, enabled: Boolean) {
        if (resetting || closed) {
            AppLog.debug("plugins", "toggle_ignored", mapOf("entityId" to id, "reason" to if (closed) "owner_closed" else "reset"))
            return
        }
        if (commands.trySend(Command.Toggle(id, enabled)).isFailure) {
            val failure = IllegalStateException("Plugin command queue closed")
            AppLog.error("plugins", "toggle_rejected", failure, mapOf("entityId" to id))
            mutableState.value = mutableState.value.copy(error = "Не удалось изменить настройки плагина. Повторите действие.")
        } else AppLog.info("plugins", "toggle_requested", mapOf("entityId" to id, "action" to if (enabled) "enable" else "disable"))
    }
    override suspend fun prepareForReset() = mutex.withLock {
        resetting = true
        if (worker != null) {
            val done = CompletableDeferred<Unit>()
            commands.send(Command.Barrier(done))
            done.await()
        }
        registry.all().filterIsInstance<PersistentPlugin>().forEach { it.prepareForReset() }
    }
    override suspend fun removeProjectDrafts(projectId: String, planIds: Set<String>?) = mutex.withLock {
        registry.all().filterIsInstance<PersistentPlugin>().forEach { it.removeProjectDrafts(projectId, planIds) }
        AppLog.info("plugins", "drafts_removed", mapOf("projectId" to projectId, "count" to (planIds?.size ?: 0).toString(), "operation" to if (planIds == null) "project" else "plans"))
    }
    override suspend fun close() {
        val pending = mutex.withLock { closed = true; commands.close(); worker }
        pending?.join()
        try {
            var failure: Exception? = null
            registry.all().filterIsInstance<PersistentPlugin>().forEach { plugin ->
                try { plugin.flushDrafts() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (next: Exception) { if (failure == null) failure = next else failure!!.addSuppressed(next) }
            }
            // Runtime shutdown owns the propagated failure and its user-visible state.
            failure?.let { throw it }
            AppLog.info("plugins", "closed")
        } finally { scope.cancel() }
    }

    private sealed interface Command {
        data class Toggle(val id: String, val enabled: Boolean) : Command
        data class Barrier(val done: CompletableDeferred<Unit>) : Command
    }
}
