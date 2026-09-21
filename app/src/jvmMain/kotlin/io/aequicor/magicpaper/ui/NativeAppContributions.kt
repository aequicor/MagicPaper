package io.aequicor.magicpaper.ui

import androidx.compose.runtime.*
import com.arkivanov.decompose.ComponentContext
import io.aequicor.magicpaper.di.NavigationEvents
import io.aequicor.magicpaper.designsystem.PaperDialog
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.domain.CodingFeature
import io.aequicor.magicpaper.domain.sidebarTitle
import io.aequicor.magicpaper.navigation.*
import io.aequicor.magicpaper.ui.components.LocalCodingPresentation
import io.aequicor.magicpaper.ui.screens.*

internal fun nativeAppContributions(feature: CodingFeature, coding: CodingService): AppContributions = AppContributions(
    routes = listOf(object : AppRouteContribution {
        override val kind = "projects"
        override fun create(context: ComponentContext, route: AppRoute, events: NavigationEvents): AppContent {
            require(route is AppRoute.Projects)
            val component = feature.componentFactory.create(context, CodingInput(route.projectId, route.sessionId)) {
                events.navigate(AppRoute.Settings(SettingsSection.MODELS))
            }
            return AppContent { CompositionLocalProvider(LocalCodingPresentation provides feature.presentation) { component.Content() } }
        }
    }),
    dialogs = listOf(NativeSessionDialog(coding)),
    sidebars = listOf(NativeSidebarContribution(coding)),
)

private class NativeSidebarContribution(private val coding: CodingService) : SidebarContribution {
    override val sourceId = "agent"
    @Composable override fun snapshot(selection: SidebarSelection, recency: SessionRecencyTracker): SidebarProjection {
        val state by coding.state.collectAsState()
        val native = state.coding
        val items = rememberNativeSidebarItems(native, selection.sessionId, selection.sourceId == sourceId, recency)
        return remember(native.sessions, native.projects, state.notice, items) {
            val names = native.projects.associate { it.id to it.name }
            SidebarProjection(
                items = items,
                search = native.sessions.map { item ->
                    val session = item.session
                    SessionSearchDocument(session.id, session.sidebarTitle(), sourceId, session.archived,
                        item.messages.lastOrNull()?.createdAt ?: session.createdAt,
                        names[session.projectId], aliases = listOf(session.name),
                        messages = item.messages.filterNot { it.systemContext || it.systemNotice }.map { it.text })
                },
                projects = native.projects.map { SidebarProject(it.id, it.name) },
                statusFilters = SidebarStatusFilter.entries,
                creationActions = listOf(SidebarCreationAction(sourceId, "📂 Новый проект")),
                notices = listOfNotNull(state.notice),
            )
        }
    }
    override fun dispatch(command: SidebarCommand) {
        when (command) {
            SidebarCommand.Create -> coding.addCodingProject()
            is SidebarCommand.CreateInProject -> coding.requestCodingSessionInProject(command.id)
            is SidebarCommand.Select -> coding.selectCodingSession(command.id)
            is SidebarCommand.Archive -> coding.archiveCodingSession(command.id)
            is SidebarCommand.Restore -> coding.restoreCodingSession(command.id)
            is SidebarCommand.Delete -> coding.deleteCodingSession(command.id)
            SidebarCommand.DismissNotice -> coding.dismissNotice()
        }
    }
}

private class NativeSessionDialog(private val coding: CodingService) : AppDialogContribution {
    override val kind = "new-session"
    @Composable override fun Content(dialog: DialogRoute, root: RootComponent<AppChild>) {
        val projects by coding.state.collectAsState()
        val projectId = dialog.entityId ?: projects.coding.current?.id
        val draft = projectId?.let(coding::sessionCreationDraft)
        if (projectId == null || draft == null) {
            PaperDialog("Новая сессия", { root.dismissDialog(dialog) }) { PaperText("Проект недоступен.") }
        } else {
            val selection by draft.state.collectAsState()
            val operations by coding.sessionCreationStatus.collectAsState()
            val operation = operations[projectId] ?: SessionCreationStatus()
            val closeCurrent = { if (root.dialogSlot.value.child?.configuration === dialog) root.dismissDialog(dialog) }
            NewCodingSessionDialog(selection.value, draft::update,
                onDismiss = closeCurrent,
                onCancel = { coding.discardCodingSessionDraft(projectId, closeCurrent) },
                onCreate = { coding.createCodingSession(projectId) { sessionId ->
                    if (root.dialogSlot.value.child?.configuration === dialog) {
                        root.dismissDialog(dialog)
                        root.navigate(AppRoute.Projects(projectId, sessionId))
                    }
                } }, loaded = selection.loaded, busy = operation.busy,
                error = selection.error?.let { if (it.committed) "Выбор сохранён. Не удалось завершить очистку."
                    else "Не удалось сохранить выбор движка. Повторите попытку." } ?: operation.error,
                onRetry = if (selection.error != null) draft::retry else null)
        }
    }
}
