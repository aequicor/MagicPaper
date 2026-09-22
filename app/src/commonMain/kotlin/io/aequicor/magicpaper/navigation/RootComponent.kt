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
import io.aequicor.magicpaper.data.storage.MachineTransitionLog
import io.aequicor.magicpaper.data.storage.NavigationSnapshotStore
import io.aequicor.magicpaper.data.storage.StorageException
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

/** Rendered projection of [NavigationMachine.State]; the machine remains the only writer. */
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
 * Sole executor of [NavigationMachine]. StateKeeper must not restore a second routing snapshot:
 * the Decompose stack is always the active prefix of the durable journal. Every decision belongs
 * to the machine; this class constructs components, mirrors the dialog slot and writes snapshots.
 */
class DefaultRootComponent<C : Any>(
    componentContext: ComponentContext,
    private val store: NavigationSnapshotStore,
    private val factory: FeatureComponentFactory<C>,
    initialDeepLink: String? = null,
    initialWelcomeRequired: Boolean? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val persistenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** What this host contributes; the machine refuses and trims everything else. */
    availability: RouteAvailability = RouteAvailability.All,
) : RootComponent<C>, ComponentContext by componentContext {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    // A complete snapshot supersedes earlier pending snapshots, including their
    // presentation entries and Forward branch. Never cancel an in-flight commit.
    private val saves = Channel<SaveRequest>(Channel.CONFLATED)
    private var saveSequence = 0L
    private val completedSave = MutableStateFlow(0L)
    private var state = NavigationMachine.initial(newNavigationId(), newNavigationId(),
        welcomeRequired = initialWelcomeRequired, availability = availability)
    private val mutableState = MutableStateFlow(projection())
    override val navigationState = mutableState.asStateFlow()
    private val navigation = StackNavigation<Visit>()
    private val dialogs = SlotNavigation<DialogRoute>()

    private var constructionJournal = state.journal
    // Decompose's Relay permanently rejects events after a child factory throws.
    // Contain construction failures inside the router, then roll back before
    // exposing or persisting the candidate transition.
    private val routerStack: Value<ChildStack<Visit, ChildCreation<C>>> = childStack(
        source = navigation,
        serializer = null,
        initialStack = { state.journal.activePrefix },
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
                    dispatch(NavigationMachine.Fact.Saved(request.journal.id))
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    // A committed record whose replaced state was not removed is not a lost write.
                    val committed = (failure as? StorageException)?.committed == true
                    AppLog.error("navigation", "save_failed", failure, mapOf("visitId" to request.journal.current.id,
                        "result" to if (committed) "committed_cleanup_unknown" else "memory_retained"))
                    dispatch(NavigationMachine.Fact.SaveFailed(request.journal.id, committed))
                }
                completedSave.value = request.sequence
            }
        }
        scope.launch {
            val restored = runCatching { store.load()?.let { json.decodeFromString<NavigationJournal>(it) } }
            (restored.exceptionOrNull() as? kotlinx.coroutines.CancellationException)?.let { throw it }
            restored.exceptionOrNull()?.let { AppLog.error("navigation", "restore_failed", it, mapOf("result" to "writes_blocked")) }
            dispatch(NavigationMachine.Fact.Restored(restored.getOrNull(), restored.isFailure))
            AppLog.info("navigation", "restored", mapOf("visitId" to state.journal.current.id,
                "route" to state.journal.current.route.logKind(), "count" to state.journal.visits.size.toString(),
                "trimmed" to (state.message == NavigationMachine.SECTION_UNAVAILABLE).toString(),
                "result" to if (state.persistenceBlocked) "failed" else "ready"))
            initialDeepLink?.let { processSafely(Command.Link(it)) }
            if (state.welcomeResolved && !state.welcomeRequired) processSafely(Command.Welcome(false))
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
        catch (failure: Exception) {
            AppLog.error("navigation", "transition_failed", failure, mapOf("visitId" to state.journal.current.id,
                "route" to state.journal.current.route.logKind()))
            dispatch(NavigationMachine.Intent.Report(NavigationMachine.TRANSITION_FAILED))
        }
    }

    private suspend fun process(command: Command) {
        when (command) {
            is Command.Navigate -> dispatch(NavigationMachine.Intent.Navigate(command.route, newNavigationId()))
            is Command.Resolve -> dispatch(NavigationMachine.Intent.Resolve(command.route))
            is Command.Reset -> dispatch(NavigationMachine.Intent.Reset(command.route, newNavigationId(), newNavigationId()))
            Command.Back -> {
                if (state.dialog != null) AppLog.info("navigation", "back", mapOf("result" to "dialog_dismissed"))
                dispatch(NavigationMachine.Intent.Back)
            }
            Command.Forward -> dispatch(NavigationMachine.Intent.Forward)
            is Command.Link -> {
                val route = AppRouteCodec.parseDeepLink(command.uri) ?: AppRouteCodec.parsePath(command.uri)
                if (route == null) AppLog.info("navigation", "link_rejected", mapOf("reason" to "unsupported_route"))
                else if (!state.availability.admits(route)) AppLog.info("navigation", "link_rejected",
                    mapOf("reason" to "unavailable_route", "route" to route.logKind()))
                else AppLog.info("navigation", "link_received", mapOf("route" to route.logKind()))
                dispatch(NavigationMachine.Intent.Link(command.uri, newNavigationId()))
            }
            is Command.BrowserVisit -> dispatch(NavigationMachine.Intent.BrowserVisit(command.journalId, command.visitId))
            is Command.Welcome -> dispatch(NavigationMachine.Intent.Welcome(command.required,
                state.journal.pendingRoutes.map { newNavigationId() }))
            is Command.Dialog -> dispatch(command.route?.let { NavigationMachine.Intent.ShowDialog(it) }
                ?: NavigationMachine.Intent.DismissDialog(null))
            is Command.DismissDialog -> dispatch(NavigationMachine.Intent.DismissDialog(command.expected))
            is Command.Presentation -> dispatch(NavigationMachine.Intent.Presentation(command.visitId, command.snapshot))
            is Command.PresentationEntry -> dispatch(NavigationMachine.Intent.Presentation(command.visitId,
                withPresentationEntry(state.journal.presentation[command.visitId], command.key, command.snapshot)))
            Command.ClearError -> dispatch(NavigationMachine.Intent.ClearError)
            is Command.Error -> dispatch(NavigationMachine.Intent.Report(command.message))
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

    private fun dispatch(input: NavigationMachine.Input) {
        val previous = state
        val transition = NavigationMachine.reduce(previous, input)
        state = transition.state
        MachineTransitionLog.append(NavigationMachine.id, NavigationMachine.space, previous, input, transition.state, transition.effects)
        publish()
        transition.effects.forEach { effect -> perform(previous, effect) }
    }

    private fun perform(previous: NavigationMachine.State, effect: NavigationMachine.Effect) {
        when (effect) {
            // Only a host that offers a control for a destination it cannot open gets here, and a
            // control that leads nowhere is a defect to find, not a routine refusal.
            is NavigationMachine.Effect.Reject ->
                if (effect.reason == NavigationMachine.ROUTE_UNAVAILABLE) AppLog.error("navigation", "route_unavailable",
                    mapOf("consequence" to "command_dropped", "visitId" to previous.journal.current.id))
                else AppLog.debug("navigation", "command_rejected", mapOf("reason" to effect.reason))
            is NavigationMachine.Effect.Project -> {
                val restoring = state.pending?.restoring == true
                try {
                    project(effect.journal, previous.journal)
                    if (!restoring) AppLog.info("navigation", "visit_changed", mapOf(
                        "visitId" to effect.journal.current.id, "from" to previous.journal.current.route.logKind(),
                        "to" to effect.journal.current.route.logKind(), "count" to effect.journal.visits.size.toString()))
                    dispatch(NavigationMachine.Fact.Projected)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    AppLog.error("navigation", if (restoring) "restore_component_failed" else "transition_failed",
                        failure, mapOf("result" to if (restoring) "writes_blocked" else "rolled_back"))
                    dispatch(NavigationMachine.Fact.ProjectionFailed)
                }
            }
            is NavigationMachine.Effect.Persist ->
                check(saves.trySend(SaveRequest(++saveSequence, effect.journal)).isSuccess)
            is NavigationMachine.Effect.ShowDialog -> dialogs.activate(effect.route)
            NavigationMachine.Effect.DismissDialog -> dialogs.dismiss()
        }
    }

    private fun project(candidate: NavigationJournal, previous: NavigationJournal) {
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

    private fun projection() = RootNavigationState(journal = state.journal, loaded = state.loaded,
        welcomeRequired = state.welcomeRequired, welcomeResolved = state.welcomeResolved, error = state.message)

    private fun publish() {
        mutableState.value = projection()
        backCallback.isEnabled = state.backEnabled
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
