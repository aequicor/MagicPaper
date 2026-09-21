package io.aequicor.magicpaper.navigation

/**
 * The destinations a host can build, fixed for the life of [NavigationMachine].
 *
 * A host lists what it contributes and the machine refuses the rest where the journal is written:
 * a route is admitted when it enters, and a restored journal is trimmed to it. No visit is ever
 * saved that this host could not open, so no screen has to exist to say "unavailable". It is a
 * value on the state and not a position because it never changes while the machine runs; a
 * position would double the nine phases for a distinction nothing moves through.
 *
 * The screens every host owns are [BASE_KINDS], [BASE_SECTIONS] and [BASE_DIALOGS]; a host adds
 * what it contributes on top with [with]. Chat is always admitted, because it is where a trimmed
 * journal lands.
 */
data class RouteAvailability(
    val kinds: Set<String>,
    val settingsSections: Set<SettingsSection>,
    /** Dialog kinds the host can draw; null admits any kind and is for fixtures that name their own. */
    val dialogKinds: Set<String>?,
) {
    init {
        require(BASE_KINDS.all { it in kinds } && BASE_SECTIONS.all { it in settingsSections })
        require(dialogKinds == null || BASE_DIALOGS.all { it in dialogKinds })
    }

    fun admits(route: AppRoute): Boolean =
        route.logKind() in kinds && (route !is AppRoute.Settings || route.section in settingsSections)

    fun admits(dialog: DialogRoute): Boolean = dialogKinds == null || dialog.kind in dialogKinds

    fun with(kinds: Set<String> = emptySet(), settingsSections: Set<SettingsSection> = emptySet(),
             dialogKinds: Set<String> = emptySet()) = RouteAvailability(
        this.kinds + kinds, this.settingsSections + settingsSections, this.dialogKinds?.plus(dialogKinds))

    /**
     * The journal a host that admits only [this] can restore. History keeps its order and its
     * visit ids. When the current visit is inadmissible the user lands on the nearest earlier
     * admissible one, as if they had gone back; with none earlier the current visit becomes Chat.
     */
    fun restrict(journal: NavigationJournal): NavigationJournal {
        val queued = journal.pendingRoutes.filter(::admits)
        val kept = journal.visits.indices.filter { admits(journal.visits[it].route) }
        if (kept.size == journal.visits.size && queued.size == journal.pendingRoutes.size) return journal
        val anchor = if (journal.cursor in kept) journal.cursor else kept.lastOrNull { it < journal.cursor }
        val visits = if (anchor != null) kept.map { journal.visits[it] }
        else listOf(journal.current.copy(route = AppRoute.Chat())) + kept.filter { it > journal.cursor }.map { journal.visits[it] }
        val ids = visits.map { it.id }.toSet()
        return journal.copy(visits = visits, cursor = if (anchor != null) kept.indexOf(anchor) else 0,
            revision = journal.revision + 1,
            // A replaced visit keeps its id but not its screen, so its presentation no longer applies.
            presentation = journal.presentation.filterKeys { it in ids && (anchor != null || it != journal.current.id) },
            pendingRoutes = queued)
    }

    companion object {
        val BASE_KINDS = setOf("chat", "docs", "plugins", "settings")
        val BASE_SECTIONS = setOf(SettingsSection.OVERVIEW, SettingsSection.MODELS, SettingsSection.PROFILE)
        val BASE_DIALOGS = setOf("chat-model", "feature-modal")

        /** What a host with no contributions can open. */
        val Base = RouteAvailability(BASE_KINDS, BASE_SECTIONS, BASE_DIALOGS)

        /** Every destination and any dialog, for fixtures that are not about availability. */
        val All = RouteAvailability(BASE_KINDS + "projects", SettingsSection.entries.toSet(), null)
    }
}
