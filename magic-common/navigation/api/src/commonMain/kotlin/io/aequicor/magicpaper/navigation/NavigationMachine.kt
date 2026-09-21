package io.aequicor.magicpaper.navigation

import io.aequicor.magicpaper.machine.Machine
import io.aequicor.magicpaper.machine.MachineId
import io.aequicor.magicpaper.machine.Step

/** Only dialog identity lives here; form and questionnaire contents belong to feature draft stores. */
data class DialogRoute(val kind: String, val entityId: String? = null)

/**
 * Sole owner of the visit journal, the welcome gate, the open dialog and the navigation notice.
 *
 * A transition is proposed before it is taken: constructing a destination can fail, and a shell
 * that had already advanced would render a screen it could not build. [State.pending] is that
 * in-flight step, and only [Fact.Projected] turns a candidate into history worth persisting.
 *
 * A failed restore leaves the durable journal unknown. The machine then keeps working in memory
 * and refuses every write until an explicit [Intent.Reset], rather than overwriting history it
 * could not read. Visit identity, like every other new id, arrives as an input value.
 */
object NavigationMachine : Machine<NavigationMachine.State, NavigationMachine.Input, NavigationMachine.Effect> {
    override val id = MachineId("navigation")
    override val space get() = NavigationSpace
    /** Bridge to the owner's own reducer: [Transition] and [reduce] keep every call site. */
    override fun step(state: State, input: Input) = reduce(state, input).let { Step(it.state, it.effects) }

    const val RESTORE_FAILED = "Не удалось восстановить историю переходов."
    const val SAVE_FAILED = "Не удалось сохранить историю переходов."
    const val TRANSITION_FAILED = "Не удалось открыть раздел. Повторите переход."
    const val LINK_UNSUPPORTED = "Ссылка не поддерживается."
    const val VISIT_UNAVAILABLE = "Этот переход больше недоступен."
    const val JOURNAL_UNAVAILABLE = "История переходов этого окна недоступна."
    const val SECTION_UNAVAILABLE = "Этот раздел недоступен на этом устройстве."
    const val ROUTE_UNAVAILABLE = "Раздел не входит в состав этого приложения"

    /** A candidate transition whose destination components are still being constructed. */
    data class Pending(val journal: NavigationJournal, val restoring: Boolean)

    @ConsistentCopyVisibility
    data class State internal constructor(
        val journal: NavigationJournal,
        val loaded: Boolean = false,
        val welcomeRequired: Boolean = false,
        val welcomeResolved: Boolean = false,
        val dialog: DialogRoute? = null,
        val pending: Pending? = null,
        val error: String? = null,
        /** Restore failed, so the durable journal is unknown and writes stay blocked. */
        val restoreError: String? = null,
        val persistenceBlocked: Boolean = false,
        /**
         * A snapshot that committed while the removal of the state it replaced did not. The
         * notice for that write is ordinary and a later one may take its place; this fact is not,
         * and only an acknowledged write clears it, because only that write collects the orphans.
         */
        val unacknowledgedCleanup: Boolean = false,
        /** What this host can build; constant for the life of the machine, see [RouteAvailability]. */
        val availability: RouteAvailability = RouteAvailability.All,
    ) {
        val ready: Boolean get() = loaded && welcomeResolved
        val route: AppRoute get() = journal.current.route
        /** An unreadable journal outranks a later notice: it explains why nothing is being saved. */
        val message: String? get() = restoreError ?: error
        val backEnabled: Boolean get() = dialog != null || (ready && !welcomeRequired && journal.canGoBack)
        /** Either the durable journal or the state it should have replaced is in an unknown condition. */
        val unknown: Boolean get() = persistenceBlocked || unacknowledgedCleanup
    }

    sealed interface Input

    sealed interface Intent : Input {
        data class Navigate(val route: AppRoute, val visitId: String) : Intent
        data class Resolve(val route: AppRoute) : Intent
        data class Reset(val route: AppRoute, val journalId: String, val visitId: String) : Intent
        data object Back : Intent
        data object Forward : Intent
        data class Link(val uri: String, val visitId: String) : Intent
        data class BrowserVisit(val journalId: String, val visitId: String) : Intent
        /** One visit id per queued route: the gate may release several deferred links at once. */
        data class Welcome(val required: Boolean, val visitIds: List<String> = emptyList()) : Intent
        data class ShowDialog(val route: DialogRoute) : Intent
        data class DismissDialog(val expected: DialogRoute?) : Intent
        data class Presentation(val visitId: String, val snapshot: String) : Intent
        data class Report(val message: String) : Intent
        data object ClearError : Intent
    }

    sealed interface Fact : Input {
        data class Restored(val journal: NavigationJournal?, val failed: Boolean) : Fact
        data object Projected : Fact
        data object ProjectionFailed : Fact
        data class SaveFailed(val journalId: String, val committed: Boolean = false) : Fact
        data class Saved(val journalId: String) : Fact
    }

    sealed interface Effect {
        data class Reject(val reason: String) : Effect
        data class Project(val journal: NavigationJournal) : Effect
        data class Persist(val journal: NavigationJournal) : Effect
        data class ShowDialog(val route: DialogRoute) : Effect
        data object DismissDialog : Effect
    }

    data class Transition(val state: State, val effects: List<Effect> = emptyList())

    fun initial(journalId: String, visitId: String, route: AppRoute = AppRoute.Chat(), welcomeRequired: Boolean? = null,
                availability: RouteAvailability = RouteAvailability.All) =
        State(journal = NavigationJournal(id = journalId, visits = listOf(Visit(visitId, route))),
            welcomeRequired = welcomeRequired ?: false, welcomeResolved = welcomeRequired != null, availability = availability)

    fun reduce(state: State, input: Input): Transition {
        fun reject(reason: String) = Transition(state, listOf(Effect.Reject(reason)))
        // A host refuses what it cannot build where the journal is written, so no visit is saved
        // that this host could not open again.
        fun admitted(route: AppRoute, then: () -> Transition) =
            if (state.availability.admits(route)) then() else reject(ROUTE_UNAVAILABLE)
        if (state.pending != null && input !is Fact.Projected && input !is Fact.ProjectionFailed) {
            return reject("Переход уже выполняется")
        }
        return when (input) {
            is Intent.Navigate -> admitted(input.route) { navigateOrQueue(state, input.route, input.visitId) }
            is Intent.Resolve -> admitted(input.route) { advance(state, state.journal.resolveCurrent(input.route)) }
            is Intent.Reset -> admitted(input.route) {
                val dismissed = dismissDialog(state)
                // An explicit reset is the one acknowledgement that the unreadable journal is gone.
                val cleared = dismissed.state.copy(error = null, restoreError = null, persistenceBlocked = false)
                val candidate = NavigationJournal(id = input.journalId, visits = listOf(Visit(input.visitId, input.route)))
                advance(cleared, candidate).let { Transition(it.state, dismissed.effects + it.effects) }
            }
            Intent.Back -> when {
                state.dialog != null -> dismissDialog(state)
                state.welcomeRequired -> Transition(state)
                else -> advance(state, state.journal.moveTo(state.journal.cursor - 1))
            }
            Intent.Forward -> when {
                state.dialog != null -> dismissDialog(state)
                state.welcomeRequired -> Transition(state)
                else -> advance(state, state.journal.moveTo(state.journal.cursor + 1))
            }
            is Intent.Link -> {
                val route = AppRouteCodec.parseDeepLink(input.uri) ?: AppRouteCodec.parsePath(input.uri)
                when {
                    route == null -> Transition(state.copy(error = LINK_UNSUPPORTED))
                    // An external link is the one way a user names a destination without a button.
                    !state.availability.admits(route) -> Transition(state.copy(error = SECTION_UNAVAILABLE))
                    else -> navigateOrQueue(state, route, input.visitId)
                }
            }
            is Intent.BrowserVisit -> {
                val dismissed = dismissDialog(state)
                val index = state.journal.visits.indexOfFirst { it.id == input.visitId }
                when {
                    input.journalId != state.journal.id ->
                        Transition(dismissed.state.copy(error = JOURNAL_UNAVAILABLE), dismissed.effects)
                    index < 0 -> Transition(dismissed.state.copy(error = VISIT_UNAVAILABLE), dismissed.effects)
                    else -> advance(dismissed.state, state.journal.moveTo(index))
                        .let { Transition(it.state, dismissed.effects + it.effects) }
                }
            }
            is Intent.Welcome -> {
                val resolved = state.copy(welcomeRequired = input.required, welcomeResolved = true)
                if (input.required) Transition(resolved) else release(resolved, input.visitIds)
            }
            is Intent.ShowDialog ->
                if (!state.availability.admits(input.route)) reject(ROUTE_UNAVAILABLE)
                else Transition(state.copy(dialog = input.route), listOf(Effect.ShowDialog(input.route)))
            // Reference identity, not equality: a completed modal must not dismiss the equal route
            // its successor reopened, and the shell keeps one route instance per live modal.
            is Intent.DismissDialog ->
                if (input.expected == null || state.dialog === input.expected) dismissDialog(state) else Transition(state)
            is Intent.Presentation -> {
                val journal = state.journal
                if (journal.visits.none { it.id == input.visitId } || journal.presentation[input.visitId] == input.snapshot) Transition(state)
                else commit(state, journal.copy(presentation = journal.presentation + (input.visitId to input.snapshot),
                    revision = journal.revision + 1))
            }
            is Intent.Report -> Transition(state.copy(error = input.message))
            Intent.ClearError -> Transition(state.copy(error = null))

            is Fact.Restored -> {
                val observed = state.copy(persistenceBlocked = input.failed,
                    restoreError = if (input.failed) RESTORE_FAILED else null)
                if (input.journal == null) Transition(observed.copy(loaded = true))
                else {
                    // A saved journal outlives the build that wrote it: project only what this host opens.
                    val journal = state.availability.restrict(input.journal)
                    Transition(observed.copy(pending = Pending(journal, restoring = true),
                        error = if (journal != input.journal) SECTION_UNAVAILABLE else observed.error),
                        listOf(Effect.Project(journal)))
                }
            }
            Fact.Projected -> {
                val pending = state.pending ?: return reject("Нет перехода в работе")
                val taken = state.copy(journal = pending.journal, pending = null)
                if (pending.restoring) Transition(taken.copy(loaded = true))
                else Transition(taken, persist(taken, pending.journal))
            }
            Fact.ProjectionFailed -> {
                val pending = state.pending ?: return reject("Нет перехода в работе")
                if (pending.restoring) Transition(state.copy(pending = null, loaded = true,
                    persistenceBlocked = true, restoreError = RESTORE_FAILED))
                else Transition(state.copy(pending = null, error = TRANSITION_FAILED))
            }
            // The notice belongs to this window's history; the orphaned state belongs to the store,
            // whichever journal was being written when its removal was left unproven.
            is Fact.SaveFailed -> Transition(state.copy(
                error = if (input.journalId == state.journal.id) SAVE_FAILED else state.error,
                unacknowledgedCleanup = state.unacknowledgedCleanup || input.committed))
            is Fact.Saved -> Transition(state.copy(unacknowledgedCleanup = false,
                error = if (state.error == SAVE_FAILED && input.journalId == state.journal.id) null else state.error))
        }
    }

    private fun persist(state: State, journal: NavigationJournal) =
        if (state.persistenceBlocked) emptyList() else listOf(Effect.Persist(journal))

    private fun dismissDialog(state: State) =
        if (state.dialog == null) Transition(state) else Transition(state.copy(dialog = null), listOf(Effect.DismissDialog))

    /** A candidate that changes the rendered stack is projected first and committed afterwards. */
    private fun advance(state: State, candidate: NavigationJournal) =
        if (candidate == state.journal) Transition(state)
        else Transition(state.copy(pending = Pending(candidate, restoring = false)), listOf(Effect.Project(candidate)))

    /** Presentation and deferred links change no destination, so they need no construction. */
    private fun commit(state: State, candidate: NavigationJournal) =
        if (candidate == state.journal) Transition(state) else Transition(state.copy(journal = candidate), persist(state, candidate))

    private fun navigateOrQueue(state: State, route: AppRoute, visitId: String): Transition {
        if (!state.welcomeResolved || state.welcomeRequired) {
            // The gate holds the destination, not the intent: the newest repeated link wins.
            if (state.journal.pendingRoutes.lastOrNull() == route) return Transition(state)
            return commit(state, state.journal.copy(pendingRoutes = state.journal.pendingRoutes + route,
                revision = state.journal.revision + 1))
        }
        val dismissed = dismissDialog(state)
        val candidate = dismissed.state.journal.navigate(route, visitId)
        return advance(dismissed.state, candidate).let { Transition(it.state, dismissed.effects + it.effects) }
    }

    private fun release(state: State, visitIds: List<String>): Transition {
        val queued = state.journal.pendingRoutes
        if (queued.isEmpty()) return Transition(state)
        if (visitIds.size < queued.size) return Transition(state, listOf(Effect.Reject("Не хватает идентификаторов переходов")))
        var candidate = state.journal.copy(pendingRoutes = emptyList(), revision = state.journal.revision + 1)
        queued.forEachIndexed { index, route -> candidate = candidate.navigate(route, visitIds[index]) }
        return advance(state, candidate)
    }
}
