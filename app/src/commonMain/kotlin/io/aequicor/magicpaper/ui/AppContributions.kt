package io.aequicor.magicpaper.ui

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import io.aequicor.magicpaper.di.NavigationEvents
import io.aequicor.magicpaper.navigation.*
import io.aequicor.magicpaper.ui.screens.*

/** A host supplies only the surfaces it owns. Saved routes outlive registrations. */
internal class AppContributions(
    routes: List<AppRouteContribution> = emptyList(),
    dialogs: List<AppDialogContribution> = emptyList(),
    sidebars: List<SidebarContribution> = emptyList(),
) {
    val routes = routes.toList()
    val dialogs = dialogs.toList()
    val sidebars = sidebars.toList()
    init {
        require(this.routes.map { it.kind }.distinct().size == this.routes.size) { "Duplicate app route contribution" }
        require(this.dialogs.map { it.kind }.distinct().size == this.dialogs.size) { "Duplicate app dialog contribution" }
        require(this.sidebars.map { it.sourceId }.distinct().size == this.sidebars.size) { "Duplicate sidebar contribution" }
        require(this.sidebars.none { it.sourceId == "chat" }) { "The chat sidebar source belongs to the common shell" }
    }
}

internal fun interface AppContent { @Composable fun Content() }
internal interface AppRouteContribution {
    val kind: String
    fun create(context: ComponentContext, route: AppRoute, events: NavigationEvents): AppContent
}
internal interface AppDialogContribution {
    val kind: String
    @Composable fun Content(dialog: DialogRoute, root: RootComponent<AppChild>)
}

internal data class SidebarSelection(val sessionId: String?, val sourceId: String)
internal data class SidebarProject(val id: String, val title: String)
internal data class SidebarCreationAction(val sourceId: String, val label: String)
internal data class SidebarProjection(
    val items: List<UnifiedSidebarItem> = emptyList(),
    val search: List<SessionSearchDocument> = emptyList(),
    val projects: List<SidebarProject> = emptyList(),
    val statusFilters: List<SidebarStatusFilter> = emptyList(),
    val creationActions: List<SidebarCreationAction> = emptyList(),
    val notices: List<String> = emptyList(),
)

internal sealed interface SidebarCommand {
    data object Create : SidebarCommand
    data class CreateInProject(val id: String) : SidebarCommand
    data class Select(val id: String) : SidebarCommand
    data class Archive(val id: String) : SidebarCommand
    data class Restore(val id: String) : SidebarCommand
    data class Delete(val id: String) : SidebarCommand
    data object DismissNotice : SidebarCommand
}
internal interface SidebarContribution {
    val sourceId: String
    @Composable fun snapshot(selection: SidebarSelection, recency: SessionRecencyTracker): SidebarProjection
    fun dispatch(command: SidebarCommand)
}
