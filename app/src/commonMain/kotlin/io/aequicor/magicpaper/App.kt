package io.aequicor.magicpaper

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import io.aequicor.magicpaper.di.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.navigation.*
import io.aequicor.magicpaper.ui.*
import io.aequicor.magicpaper.ui.components.*
import io.aequicor.magicpaper.ui.screens.UnifiedSidebar
import io.aequicor.magicpaper.ui.screens.NewCodingSessionDialog
import io.aequicor.magicpaper.ui.window.*
import kotlinx.coroutines.delay

/** Host-created runtime and root survive recomposition; this layer only renders Paper. */
@Composable
fun App(runtime: MagicPaperRuntime, root: RootComponent<AppChild>) {
    val readiness by runtime.ready.collectAsState()
    PaperTheme {
        CompositionLocalProvider(
            LocalChatPresentation provides DefaultChatPresentation,
            LocalModelPresentation provides DefaultModelPresentation,
            LocalCodingPresentation provides DefaultCodingPresentation,
        ) {
            PaperSurface(Modifier.fillMaxSize(), kind = PaperSurfaceKind.CANVAS) {
                when (val status = readiness) {
                    RuntimeState.Ready -> AppShell(runtime, root)
                    is RuntimeState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PaperText(status.message) }
                    RuntimeState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PaperText("Загрузка…") }
                    RuntimeState.Closed -> Unit
                }
            }
        }
    }
}

@Composable
private fun AppShell(runtime: MagicPaperRuntime, root: RootComponent<AppChild>) {
    val settings = runtime.koin.get<SettingsService>()
    val config by settings.state.collectAsState()
    val navigation by root.navigationState.collectAsState()
    val visit = navigation.journal.current
    val presentation = remember(visit.id) {
        VisitPresentationState(presentationEntry(navigation.journal.presentation[visit.id], "shell"), onError = root::reportNavigationError) {
            root.savePresentationEntry(visit.id, "shell", it)
        }
    }
    SideEffect { (root as ApplicationRoot).shellPresentation = presentation }
    Box(Modifier.fillMaxSize()) {
        // The animation belongs to the window, not a visit: recreating it resets
        // its clock and shader and makes the entire window flash on navigation.
        PaperBackground(config.settings.paperAnimationEnabled, Modifier.matchParentSize())
        key(visit.id) { presentation.Content { AppShellContent(runtime, root) } }
    }
}

@Composable
private fun AppShellContent(runtime: MagicPaperRuntime, root: RootComponent<AppChild>) {
    val settings = runtime.koin.get<SettingsService>()
    val chat = runtime.koin.get<ChatService>()
    val coding = runtime.koin.get<CodingService>()
    val usage = runtime.koin.get<UsageLedger>()
    val config by settings.state.collectAsState()
    val chats by chat.state.collectAsState()
    val projects by coding.state.collectAsState()
    val navigation by root.navigationState.collectAsState()
    val stack by root.stack.subscribeAsState()
    val slot by root.dialogSlot.subscribeAsState()
    var sidebarVisible by rememberSaveable { mutableStateOf(true) }
    val sidebarActions = remember(chat, coding) { SidebarActions(chat, coding) }
    val isCoding = navigation.route is AppRoute.Projects
    val selectedId = when(val route = navigation.route) {
        is AppRoute.Chat -> route.sessionId
        is AppRoute.Projects -> route.sessionId
        else -> null
    }
    val dialogLifecycle = remember(root) { RootDialogLifecycle(root) }
    DisposableEffect(dialogLifecycle) { onDispose { dialogLifecycle.close() } }
    CompositionLocalProvider(LocalPaperHideSystemSteps provides config.settings.hideSystemSteps,
        LocalPaperDialogLifecycle provides dialogLifecycle) {
        Box(Modifier.fillMaxSize()) {
            if (config.showWelcome) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) { (root as ApplicationRoot).welcome.Content() }
            } else {
                // The bar overlays the content: transcripts run edge-to-edge behind it,
                // the frost band keeps its buttons legible over scrolled messages and ends
                // in a hairline; pinned surfaces keep a lane below that hairline.
                val topInset = LocalWindowToolbarHeight.current ?: 56.dp
                val edgeToEdge = navigation.route is AppRoute.Chat || navigation.route is AppRoute.Projects
                Box(Modifier.fillMaxSize().navigationBarsPadding().paperTitleBarFrost(topInset)) {
                    PaperResizablePanels(sidebarVisible = sidebarVisible,
                        sidebar = { modifier ->
                            UnifiedSidebar(sidebarActions, chats.sessions, projects.coding, selectedId, isCoding,
                                modifier.padding(top = topInset))
                        }) {
                        Box(Modifier.fillMaxSize().then(if (edgeToEdge) Modifier else Modifier.padding(top = topInset))) {
                            key(stack.active.configuration.id) { stack.active.instance.Content() }
                        }
                    }
                    Notice(navigation.error ?: config.notice ?: chats.notice ?: projects.notice,
                        Modifier.align(Alignment.BottomCenter)) {
                        root.dismissNavigationError(); settings.dismissNotice(); chat.dismissNotice(); coding.dismissNotice()
                    }
                }
                TopBar(root, usage, selectedId, isCoding, onToggleSidebar = { sidebarVisible = !sidebarVisible })
            }
            CompositionLocalProvider(LocalPaperDialogLifecycle provides null) {
            slot.child?.configuration?.let { dialog ->
                when (dialog.kind) {
                    "chat-model" -> {
                        val resolved = ProfileResolver.resolve(chats.current, chats.settings, chats.availableLlmProfiles)
                        val selection = chats.current?.modelSelection ?: resolved?.let { ModelSelection(it.id, it.selectionKey, it.effortSelectionFor()) }
                        FavoriteModelPicker(chats.availableLlmProfiles, selection,
                            { chat.selectChatModel(it); root.dismissDialog() }, root::dismissDialog, "Модель чата", footer = {
                                PaperAction({ root.dismissDialog(); root.navigate(AppRoute.Settings(SettingsSection.MODELS)) }) { PaperText("Настроить модели") }
                            })
                    }
                    "new-session" -> {
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
            }
            }
        }
    }
}

@Composable
private fun TopBar(root: RootComponent<AppChild>, usage: UsageLedger, selectedId: String?, isCoding: Boolean, onToggleSidebar: () -> Unit) {
    val navigation by root.navigationState.collectAsState()
    val modal by root.dialogSlot.subscribeAsState()
    val chrome = LocalWindowChrome.current
    val toolbarHeight = LocalWindowToolbarHeight.current ?: 56.dp
    val layoutDirection = LocalLayoutDirection.current
    val nativeInsets = LocalWindowTitleBarInsets.current
    WindowTitleBarArea(Modifier.fillMaxWidth().height(toolbarHeight)) {
        Row(Modifier.statusBarsPadding().fillMaxSize().padding(
            start = nativeInsets.calculateLeftPadding(layoutDirection).coerceAtLeast(8.dp),
            end = nativeInsets.calculateRightPadding(layoutDirection).coerceAtLeast(8.dp)), verticalAlignment = Alignment.CenterVertically) {
            PaperIconButton("Показать или скрыть боковую панель", onToggleSidebar) { PaperText("☰", role = PaperTextRole.CHROME) }
            PaperIconButton("Назад", root::back, enabled = navigation.canGoBack || modal.child != null) { PaperText("‹", role = PaperTextRole.CHROME) }
            PaperIconButton("Вперёд", root::forward, enabled = navigation.canGoForward) { PaperText("›", role = PaperTextRole.CHROME) }
            WindowDragArea(Modifier.weight(1f).height(toolbarHeight)) {
                Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PaperText("MagicPaper", role = PaperTextRole.CHROME, maxLines = 1)
                    Spacer(Modifier.width(10.dp))
                    PaperText(when (navigation.route) {
                        is AppRoute.Chat -> "Шалость удалась"
                        is AppRoute.Projects -> "Проекты и код"
                        is AppRoute.Settings -> "Настройки и разделы"
                        is AppRoute.Docs -> "Справочник"
                        is AppRoute.Plugins -> "Плагины и панели"
                    }, Modifier.weight(1f), role = PaperTextRole.CHROME, color = LocalPaperColors.current.secondaryText,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val usageState by usage.state.collectAsState()
            val usageFailure by usage.failure.collectAsState()
            UsageMenu(usageState, selectedId?.let { if (isCoding) "coding:$it" else "chat:$it" }, usageFailure)
            PaperIconButton("Настройки", {
                if (navigation.route is AppRoute.Settings && navigation.canGoBack) root.back()
                else root.navigate(AppRoute.Settings())
            }, selected = navigation.route is AppRoute.Settings) { PaperText("⚙", role = PaperTextRole.CHROME) }
            if (chrome != null) WindowButtons(chrome)
        }
    }
}

/** Кнопки управления окном на десктопе: свернуть, развернуть, закрыть. */
@Composable
private fun WindowButtons(chrome: io.aequicor.magicpaper.ui.window.WindowChrome) {
    WindowButton("─") { chrome.minimize() }
    WindowButton("▢") { chrome.toggleMaximize() }
    WindowButton("✕", danger = true) { chrome.close() }
}

@Composable
private fun WindowButton(glyph: String, danger: Boolean = false, onClick: () -> Unit) {
    PaperIconButton("Управление окном", onClick, Modifier.size(40.dp)) {
        PaperText(glyph, role = PaperTextRole.TITLE, color = if (danger) LocalPaperColors.current.error else LocalPaperColors.current.secondaryText)
    }
}

@Composable
private fun Notice(notice: String?, modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    AnimatedVisibility(
        visible = notice != null,
        modifier = modifier,
    ) {
        LaunchedEffect(notice) {
            delay(4000)
            onDismiss()
        }
        PaperPanel(Modifier.padding(16.dp).padding(horizontal = 16.dp, vertical = 10.dp), kind = PaperSurfaceKind.RAISED) {
            PaperText(notice.orEmpty())
        }
    }
}
