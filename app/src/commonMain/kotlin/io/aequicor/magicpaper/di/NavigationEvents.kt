package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.navigation.AppRoute
import io.aequicor.magicpaper.navigation.DialogRoute
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** Application actions are delivered once to the current root, including host recreation. */
class NavigationEvents {
    sealed interface Event {
        data class Navigate(val route: AppRoute) : Event
        data class Dialog(val route: DialogRoute) : Event
        data object Back : Event
        data class ResetComplete(val completed: kotlinx.coroutines.CompletableDeferred<Unit>) : Event
        data class Reset(val completed: kotlinx.coroutines.CompletableDeferred<Unit>) : Event
    }
    private val queue = Channel<Event>(Channel.UNLIMITED)
    val events = queue.receiveAsFlow()
    fun navigate(route: AppRoute) { queue.trySend(Event.Navigate(route)).getOrThrow() }
    fun dialog(route: DialogRoute) { queue.trySend(Event.Dialog(route)).getOrThrow() }
    fun back() { queue.trySend(Event.Back).getOrThrow() }
    suspend fun resetComplete() {
        val completed = kotlinx.coroutines.CompletableDeferred<Unit>()
        queue.send(Event.ResetComplete(completed))
        completed.await()
    }
    suspend fun reset() {
        val completed = kotlinx.coroutines.CompletableDeferred<Unit>()
        queue.send(Event.Reset(completed))
        completed.await()
    }
}
