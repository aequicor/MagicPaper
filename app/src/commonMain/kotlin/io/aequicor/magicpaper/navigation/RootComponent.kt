package io.aequicor.magicpaper.navigation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.Child
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.router.slot.SlotNavigation
import com.arkivanov.decompose.router.slot.activate
import com.arkivanov.decompose.router.slot.childSlot
import com.arkivanov.decompose.router.slot.dismiss
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.essenty.backhandler.BackCallback
import com.arkivanov.essenty.lifecycle.doOnDestroy
import io.aequicor.magicpaper.data.storage.NavigationSnapshotStore
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

fun interface FeatureComponentFactory<C : Any> {
    fun create(visit: Visit, context: ComponentContext, presentation: String?): C
}

/** Only dialog identity lives here; form and questionnaire contents belong to feature draft stores. */
data class DialogRoute(val kind: String, val entityId: String? = null)

data class RootNavigationState(
    val journal: NavigationJournal,
    val loaded: Boolean = false,
    val welcomeRequired: Boolean = false,
    val welcomeResolved: Boolean = false,
    val error: String? = null,
) {
    val ready: Boolean get() = loaded && welcomeResolved
    val route: AppRoute get() = journal.current.route
    val canGoBack: Boolean get() = journal.canGoBack
    val canGoForward: Boolean get() = journal.canGoForward
}

interface RootComponent<C : Any> {
    val stack: Value<ChildStack<Visit, C>>
    val dialogSlot: Value<ChildSlot<DialogRoute, DialogRoute>>
    val navigationState: StateFlow<RootNavigationState>
    fun navigate(route: AppRoute)
    fun resolveCurrent(route: AppRoute)
    fun reset(route: AppRoute = AppRoute.Chat())
    fun back()
    fun forward()
    fun handleDeepLink(uri: String)
    fun onBrowserVisit(journalId: String, visitId: String)
    fun setWelcomeRequired(required: Boolean)
    fun showDialog(dialog: DialogRoute)
    fun dismissDialog(expected: DialogRoute? = null)
    fun savePresentation(visitId: String, snapshot: String)
    fun savePresentationEntry(visitId: String, key: String, snapshot: String)
    fun dismissNavigationError()
    fun reportNavigationError(message: String)
    suspend fun awaitIdle()
}

/**
 * Sole navigation command coordinator. StateKeeper must not restore a second routing snapshot:
 * the Decompose stack is always the active prefix of the durable journal.
 */
class DefaultRootComponent<C : Any>(
    componentContext: ComponentContext,
    private val store: NavigationSnapshotStore,
    private val factory: FeatureComponentFactory<C>,
    initialDeepLink: String? = null,
    initialWelcomeRequired: Boolean? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val persistenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : RootComponent<C>, ComponentContext by componentContext {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    // A complete snapshot supersedes earlier pending snapshots, including their
    // presentation entries and Forward branch. Never cancel an in-flight commit.
    private val saves = Channel<SaveRequest>(Channel.CONFLATED)
    private var saveSequence = 0L
    private val completedSave = MutableStateFlow(0L)
    private var journal = NavigationJournal()
    private var persistenceBlocked = false
    private var restoreError: String? = null
    private val mutableState = MutableStateFlow(RootNavigationState(journal = journal,
        welcomeRequired = initialWelcomeRequired ?: false, welcomeResolved = initialWelcomeRequired != null))
    override val navigationState = mutableState.asStateFlow()
    private val navigation = StackNavigation<Visit>()
    private val dialogs = SlotNavigation<DialogRoute>()

    private var constructionJournal = journal
    // Decompose's Relay permanently rejects events after a child factory throws.
    // Contain construction failures inside the router, then roll back before
    // exposing or persisting the candidate transition.
    private val routerStack: Value<ChildStack<Visit, ChildCreation<C>>> = childStack(
        source = navigation,
        serializer = null,
        initialStack = { journal.activePrefix },
        handleBackButton = false,
        childFactory = { visit, context ->
            try { ChildCreation(factory.create(visit, context, constructionJournal.presentation[visit.id]), null) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { ChildCreation(null, failure) }
        },
    )
    private val mutableStack = MutableValue(unwrap(routerStack.value))
    override val stack: Value<ChildStack<Visit, C>> = mutableStack
    override val dialogSlot: Value<ChildSlot<DialogRoute, DialogRoute>> = childSlot(
        source = dialogs,
        serializer = null,
        handleBackButton = false,
        childFactory = { dialog, _ -> dialog },
    )

    private val backCallback = BackCallback(isEnabled = false) { back() }

    init {
        backHandler.register(backCallback)
        lifecycle.doOnDestroy { commands.close(); saves.close(); scope.cancel() }
        scope.launch {
            for (request in saves) {
                try {
                    withContext(persistenceDispatcher) { store.save(json.encodeToString(request.journal)) }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    AppLog.error("navigation", "save_failed", failure, mapOf("visitId" to request.journal.current.id,
                        "result" to "memory_retained"))
                    if (request.journal.id == journal.id) publish(error = "Не удалось сохранить историю переходов.")
                }
                completedSave.value = request.sequence
            }
        }
        scope.launch {
            val restored = runCatching { store.load()?.let { json.decodeFromString<NavigationJournal>(it) } }
            (restored.exceptionOrNull() as? kotlinx.coroutines.CancellationException)?.let { throw it }
            persistenceBlocked = restored.isFailure
            restoreError = restored.exceptionOrNull()?.let { "Не удалось восстановить историю переходов." }
            restored.exceptionOrNull()?.let { AppLog.error("navigation", "restore_failed", it, mapOf("result" to "writes_blocked")) }
            restored.getOrNull()?.let { candidate ->
                try { project(candidate); journal = candidate }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    persistenceBlocked = true
                    restoreError = "Не удалось восстановить историю переходов."
                    AppLog.error("navigation", "restore_component_failed", failure, mapOf("result" to "writes_blocked"))
                }
            }
            AppLog.info("navigation", "restored", mapOf("visitId" to journal.current.id,
                "route" to journal.current.route.logKind(), "count" to journal.visits.size.toString(),
                "result" to if (persistenceBlocked) "failed" else "ready"))
            publish(error = restoreError, loaded = true)
            initialDeepLink?.let { processSafely(Command.Link(it)) }
            if (mutableState.value.welcomeResolved && !mutableState.value.welcomeRequired) processSafely(Command.Welcome(false))
            for (command in commands) processSafely(command)
        }
    }

    override fun navigate(route: AppRoute) = send(Command.Navigate(route))
    override fun resolveCurrent(route: AppRoute) = send(Command.Resolve(route))
    override fun reset(route: AppRoute) = send(Command.Reset(route))
    override fun back() = send(Command.Back)
    override fun forward() = send(Command.Forward)
    override fun handleDeepLink(uri: String) = send(Command.Link(uri))
    override fun onBrowserVisit(journalId: String, visitId: String) = send(Command.BrowserVisit(journalId, visitId))
    override fun setWelcomeRequired(required: Boolean) = send(Command.Welcome(required))
    override fun showDialog(dialog: DialogRoute) = send(Command.Dialog(dialog))
    override fun dismissDialog(expected: DialogRoute?) = send(Command.DismissDialog(expected))
    override fun savePresentation(visitId: String, snapshot: String) = send(Command.Presentation(visitId, snapshot))
    override fun savePresentationEntry(visitId: String, key: String, snapshot: String) = send(Command.PresentationEntry(visitId, key, snapshot))
    override fun dismissNavigationError() = send(Command.ClearError)
    override fun reportNavigationError(message: String) = send(Command.Error(message))

    override suspend fun awaitIdle() {
        val done = CompletableDeferred<Unit>()
        commands.send(Command.Barrier(done))
        done.await()
    }

    private fun send(command: Command) {
        if (commands.trySend(command).isFailure) AppLog.debug("navigation", "command_discarded", mapOf("reason" to "owner_closed"))
    }

    private suspend fun processSafely(command: Command) {
        try { process(command) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { navigationFailure(failure) }
    }

    private fun navigationFailure(failure: Exception) {
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        AppLog.error("navigation", "transition_failed", failure, mapOf("visitId" to journal.current.id, "route" to journal.current.route.logKind()))
        publish(error = "Не удалось открыть раздел. Повторите переход.")
    }

    private suspend fun process(command: Command) {
        when (command) {
            is Command.Navigate -> navigateOrQueue(command.route)
            is Command.Resolve -> update(journal.resolveCurrent(command.route))
            is Command.Reset -> {
                persistenceBlocked = false
                restoreError = null
                mutableState.value = mutableState.value.copy(error = null)
                dialogs.dismiss()
                update(NavigationJournal(visits = listOf(Visit(newNavigationId(), command.route))))
                refreshBack()
            }
            Command.Back -> if (dialogSlot.value.child != null) { AppLog.info("navigation", "back", mapOf("result" to "dialog_dismissed")); dialogs.dismiss(); refreshBack() }
                else if (!mutableState.value.welcomeRequired) update(journal.moveTo(journal.cursor - 1))
            Command.Forward -> if (dialogSlot.value.child != null) { dialogs.dismiss(); refreshBack() }
                else if (!mutableState.value.welcomeRequired) update(journal.moveTo(journal.cursor + 1))
            is Command.Link -> acceptLink(command.uri)
            is Command.BrowserVisit -> {
                dialogs.dismiss()
                if (command.journalId == journal.id) {
                    val index = journal.visits.indexOfFirst { it.id == command.visitId }
                    if (index >= 0) update(journal.moveTo(index))
                    else publish(error = "Этот переход больше недоступен.")
                } else publish(error = "История переходов этого окна недоступна.")
                refreshBack()
            }
            is Command.Welcome -> {
                mutableState.value = mutableState.value.copy(welcomeRequired = command.required, welcomeResolved = true)
                if (!command.required) drainPendingRoutes()
                refreshBack()
            }
            is Command.Dialog -> {
                if (command.route == null) dialogs.dismiss() else dialogs.activate(command.route)
                refreshBack()
            }
            is Command.DismissDialog -> {
                if (command.expected == null || dialogSlot.value.child?.configuration === command.expected) dialogs.dismiss()
                refreshBack()
            }
            is Command.Presentation -> if (journal.visits.any { it.id == command.visitId }) {
                if (journal.presentation[command.visitId] != command.snapshot) update(journal.copy(
                    presentation = journal.presentation + (command.visitId to command.snapshot), revision = journal.revision + 1,
                ), projectStack = false)
            }
            is Command.PresentationEntry -> if (journal.visits.any { it.id == command.visitId }) {
                val snapshot = withPresentationEntry(journal.presentation[command.visitId], command.key, command.snapshot)
                if (journal.presentation[command.visitId] != snapshot) update(journal.copy(
                    presentation = journal.presentation + (command.visitId to snapshot), revision = journal.revision + 1,
                ), projectStack = false)
            }
            Command.ClearError -> publish(error = null)
            is Command.Error -> publish(error = command.message)
            is Command.Barrier -> {
                val target = saveSequence
                // Flush waits for the writer without holding up later UI commands.
                scope.launch {
                    completedSave.first { it >= target }
                    command.done.complete(Unit)
                }
            }
        }
    }

    private suspend fun acceptLink(uri: String) {
        val route = AppRouteCodec.parseDeepLink(uri) ?: AppRouteCodec.parsePath(uri)
        if (route == null) {
            AppLog.info("navigation", "link_rejected", mapOf("reason" to "unsupported_route"))
            publish(error = "Ссылка не поддерживается.")
        } else {
            AppLog.info("navigation", "link_received", mapOf("route" to route.logKind()))
            navigateOrQueue(route)
        }
    }

    private suspend fun navigateOrQueue(route: AppRoute) {
        if (!mutableState.value.welcomeResolved || mutableState.value.welcomeRequired) {
            AppLog.debug("navigation", "route_deferred", mapOf("reason" to "welcome", "route" to route.logKind()))
            if (journal.pendingRoutes.lastOrNull() != route) update(journal.copy(
                pendingRoutes = journal.pendingRoutes + route, revision = journal.revision + 1,
            ), projectStack = false)
        } else {
            dialogs.dismiss()
            update(journal.navigate(route))
            refreshBack()
        }
    }

    private suspend fun drainPendingRoutes() {
        val pending = journal.pendingRoutes
        if (pending.isEmpty()) return
        var next = journal.copy(pendingRoutes = emptyList(), revision = journal.revision + 1)
        pending.forEach { next = next.navigate(it) }
        update(next)
    }

    private suspend fun update(next: NavigationJournal, projectStack: Boolean = true) {
        if (next == journal) return
        val previous = journal
        if (projectStack) project(next)
        journal = next
        if (projectStack) AppLog.info("navigation", "visit_changed", mapOf("visitId" to journal.current.id,
            "from" to previous.current.route.logKind(), "to" to journal.current.route.logKind(),
            "count" to journal.visits.size.toString()))
        publish()
        if (persistenceBlocked) return
        check(saves.trySend(SaveRequest(++saveSequence, journal)).isSuccess)
    }

    private fun project(candidate: NavigationJournal) {
        val previous = journal
        constructionJournal = candidate
        navigation.navigate(transformer = { candidate.activePrefix }, onComplete = { _, _ -> })
        val failure = routerStack.value.items.firstNotNullOfOrNull { it.instance.failure }
        if (failure != null) {
            constructionJournal = previous
            navigation.navigate(transformer = { previous.activePrefix }, onComplete = { _, _ -> })
            mutableStack.value = unwrap(routerStack.value)
            throw failure
        }
        mutableStack.value = unwrap(routerStack.value)
    }

    private fun unwrap(value: ChildStack<Visit, ChildCreation<C>>): ChildStack<Visit, C> {
        fun child(value: Child.Created<Visit, ChildCreation<C>>): Child.Created<Visit, C> {
            value.instance.failure?.let { throw it }
            return Child.Created(value.configuration, requireNotNull(value.instance.component), value.key)
        }
        return ChildStack(active = child(value.active), backStack = value.backStack.map(::child))
    }

    private data class ChildCreation<C : Any>(val component: C?, val failure: Exception?)
    private data class SaveRequest(val sequence: Long, val journal: NavigationJournal)

    private fun publish(error: String? = mutableState.value.error, loaded: Boolean = mutableState.value.loaded) {
        mutableState.value = mutableState.value.copy(journal = journal, loaded = loaded, error = restoreError ?: error)
        refreshBack()
    }

    private fun refreshBack() {
        backCallback.isEnabled = dialogSlot.value.child != null ||
            (mutableState.value.ready && !mutableState.value.welcomeRequired && journal.canGoBack)
    }

    private sealed interface Command {
        data class Navigate(val route: AppRoute) : Command
        data class Resolve(val route: AppRoute) : Command
        data class Reset(val route: AppRoute) : Command
        data object Back : Command
        data object Forward : Command
        data class Link(val uri: String) : Command
        data class BrowserVisit(val journalId: String, val visitId: String) : Command
        data class Welcome(val required: Boolean) : Command
        data class Dialog(val route: DialogRoute?) : Command
        data class DismissDialog(val expected: DialogRoute?) : Command
        data class Presentation(val visitId: String, val snapshot: String) : Command
        data class PresentationEntry(val visitId: String, val key: String, val snapshot: String) : Command
        data class Error(val message: String) : Command
        data object ClearError : Command
        data class Barrier(val done: CompletableDeferred<Unit>) : Command
    }
}
