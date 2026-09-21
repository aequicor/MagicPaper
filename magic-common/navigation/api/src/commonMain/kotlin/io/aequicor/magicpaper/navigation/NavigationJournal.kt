package io.aequicor.magicpaper.navigation

import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
data class Visit(val id: String, val route: AppRoute)

/** Full history, including the forward branch, independent of the rendered ChildStack prefix. */
@Serializable
data class NavigationJournal(
    val id: String = newNavigationId(),
    val visits: List<Visit> = listOf(Visit(newNavigationId(), AppRoute.Chat())),
    val cursor: Int = 0,
    val revision: Long = 0,
    val presentation: Map<String, String> = emptyMap(),
    val pendingRoutes: List<AppRoute> = emptyList(),
    val version: Int = 1,
) {
    init {
        require(version == 1 && id.isNotBlank() && revision >= 0)
        require(visits.isNotEmpty() && cursor in visits.indices)
        require(visits.all { it.id.isNotBlank() } && visits.map { it.id }.distinct().size == visits.size)
    }

    val current: Visit get() = visits[cursor]
    val canGoBack: Boolean get() = cursor > 0
    val canGoForward: Boolean get() = cursor < visits.lastIndex
    val activePrefix: List<Visit> get() = visits.take(cursor + 1)

    fun navigate(route: AppRoute, visitId: String = newNavigationId()): NavigationJournal {
        if (current.route == route) return this
        val next = activePrefix + Visit(visitId, route)
        val ids = next.map { it.id }.toSet()
        return copy(visits = next, cursor = next.lastIndex, revision = revision + 1,
            presentation = presentation.filterKeys { it in ids })
    }

    fun moveTo(index: Int): NavigationJournal =
        if (index !in visits.indices || index == cursor) this else copy(cursor = index, revision = revision + 1)

    /** Resolves a section alias to a concrete entity without inventing an extra history visit. */
    fun resolveCurrent(route: AppRoute): NavigationJournal =
        if (current.route == route) this else copy(
            visits = visits.mapIndexed { index, visit -> if (index == cursor) visit.copy(route = route) else visit },
            revision = revision + 1,
        )
}

fun newNavigationId(): String = buildString {
    repeat(4) { append(Random.nextLong().toULong().toString(16).padStart(16, '0')) }
}
