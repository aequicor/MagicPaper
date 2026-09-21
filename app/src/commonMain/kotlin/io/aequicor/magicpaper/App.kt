package io.aequicor.magicpaper

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import io.aequicor.magicpaper.di.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.navigation.*
import io.aequicor.magicpaper.ui.*
import io.aequicor.magicpaper.ui.components.*
import io.aequicor.magicpaper.ui.screens.UnifiedSidebar
import io.aequicor.magicpaper.ui.screens.SessionRecencyTracker
import io.aequicor.magicpaper.util.Id
import io.aequicor.magicpaper.ui.window.*
import kotlinx.coroutines.delay

/** Host-created runtime and root survive recomposition; this layer only renders Paper. */
@Composable
fun App(runtime: MagicPaperRuntime, root: RootComponent<AppChild>, compact: Boolean = false) {
    val readiness by runtime.ready.collectAsState()
    PaperTheme {
        CompositionLocalProvider(
            LocalChatPresentation provides DefaultChatPresentation,
            LocalModelPresentation provides DefaultModelPresentation,
        ) {
            PaperSurface(Modifier.fillMaxSize(), kind = PaperSurfaceKind.CANVAS) {
                when (val status = readiness) {
                    RuntimeState.Ready -> {
                        val configuration by runtime.koin.get<SettingsService>().state.collectAsState()
                        GeneratedMediaProvider(runtime.koin.get<io.aequicor.magicpaper.data.storage.MediaStore>(),
                            runtime.koin.get<MediaGenerationService>(), animate = configuration.settings.paperAnimationEnabled) {
                            AppShell(runtime, root, compact)
                        }
                    }
                    is RuntimeState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PaperText(status.message) }
                    RuntimeState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PaperText("Загрузка…") }
                    RuntimeState.Closed -> Unit
                }
            }
        }
    }
}

@Composable
private fun AppShell(runtime: MagicPaperRuntime, root: RootComponent<AppChild>, compact: Boolean) {
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
    // Navigation recreates the visit content below. Recency belongs to the app shell,
    // otherwise selecting another session would reset the activity ordering.
    val sidebarRecencyTracker = remember { SessionRecencyTracker(Id::now) }
    // Shell layout is a window preference, not a visit preference. Keep it above the
    // per-visit registry so selecting another session cannot reset it.
    var primarySidebarVisible by rememberSaveable { mutableStateOf(true) }
    var chatSidebarVisible by rememberSaveable { mutableStateOf(true) }
    var compactSidebarVisible by remember(compact) { mutableStateOf(false) }
    var primarySidebarWidth by rememberSaveable { mutableStateOf(272f) }
    var chatSidebarWidth by rememberSaveable { mutableStateOf(272f) }
    // Selection replaces the visit composition. The global lists belong to the
    // window so a new selected session must not recreate either scroll position.
    val primarySidebarListState = rememberLazyListState()
    val chatSidebarListState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        // The animation belongs to the window, not a visit: recreating it resets
        // its clock and shader and makes the entire window flash on navigation.
        PaperBackground(config.settings.paperAnimationEnabled, Modifier.matchParentSize())
        key(visit.id) { presentation.Content {
            AppShellContent(runtime, root, sidebarRecencyTracker,
                if (compact) compactSidebarVisible else primarySidebarVisible,
                if (compact) compactSidebarVisible else chatSidebarVisible, primarySidebarWidth, chatSidebarWidth,
                primarySidebarListState, chatSidebarListState,
                { if (compact) compactSidebarVisible = it else primarySidebarVisible = it },
                { if (compact) compactSidebarVisible = it else chatSidebarVisible = it },
                { primarySidebarWidth = it }, { chatSidebarWidth = it })
        } }
    }
}

@Composable
private fun AppShellContent(
    runtime: MagicPaperRuntime,
    root: RootComponent<AppChild>,
    sidebarRecencyTracker: SessionRecencyTracker,
    primarySidebarVisible: Boolean,
    chatSidebarVisible: Boolean,
    primarySidebarWidth: Float,
    chatSidebarWidth: Float,
    primarySidebarListState: LazyListState,
    chatSidebarListState: LazyListState,
    onPrimarySidebarVisibleChange: (Boolean) -> Unit,
    onChatSidebarVisibleChange: (Boolean) -> Unit,
    onPrimarySidebarWidthChange: (Float) -> Unit,
    onChatSidebarWidthChange: (Float) -> Unit,
) {
    val settings = runtime.koin.get<SettingsService>()
    val chat = runtime.koin.get<ChatService>()
    val contributions = runtime.koin.get<AppContributions>()
    val usage = runtime.koin.get<UsageLedger>()
    val config by settings.state.collectAsState()
    val chats by chat.state.collectAsState()
    val navigation by root.navigationState.collectAsState()
    val stack by root.stack.subscribeAsState()
    val slot by root.dialogSlot.subscribeAsState()
    val sidebarActions = remember(chat, contributions) { SidebarActions(chat, contributions.sidebars) }
    val isCoding = navigation.route is AppRoute.Projects
    val isChat = navigation.route is AppRoute.Chat
    val sidebarVisible = if (isChat) chatSidebarVisible else primarySidebarVisible
    val selectedId = when(val route = navigation.route) {
        is AppRoute.Chat -> route.sessionId
        is AppRoute.Projects -> route.sessionId
        else -> null
    }
    val sidebarProjections = contributions.sidebars.map { contribution ->
        key(contribution.sourceId) { contribution.snapshot(SidebarSelection(selectedId, if (isCoding) "agent" else "chat"), sidebarRecencyTracker) }
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
                // Research panes start below the title bar; only edge-to-edge coding
                // transcripts need their content captured again for backdrop blur.
                Box(Modifier.fillMaxSize().navigationBarsPadding().paperTitleBarFrost(topInset, blurContent = isCoding)) {
                    PaperResizablePanels(sidebarVisible = sidebarVisible,
                        preferredWidth = if (isChat) chatSidebarWidth else primarySidebarWidth,
                        onPreferredWidthChange = if (isChat) onChatSidebarWidthChange else onPrimarySidebarWidthChange,
                        sidebar = { modifier ->
                            UnifiedSidebar(sidebarActions, chats.notebooks, sidebarProjections,
                                if (isCoding) selectedId else chats.sessions.firstOrNull { it.id == selectedId }?.researchChatId ?: selectedId, isCoding,
                                modifier.padding(top = topInset),
                                if (isChat) chatSidebarListState else primarySidebarListState)
                        }) {
                        Box(Modifier.fillMaxSize().then(if (edgeToEdge) Modifier else Modifier.padding(top = topInset))) {
                            key(stack.active.configuration.id) { stack.active.instance.Content() }
                        }
                    }
                    Notice(navigation.error ?: config.notice ?: chats.notice ?: sidebarProjections.firstNotNullOfOrNull { it.notices.firstOrNull() },
                        Modifier.align(Alignment.BottomCenter)) {
                        root.dismissNavigationError(); settings.dismissNotice(); chat.dismissNotice()
                        contributions.sidebars.forEach { it.dispatch(SidebarCommand.DismissNotice) }
                    }
                }
                TopBar(
                    root = root,
                    usage = usage,
                    selectedId = if (isChat) chats.current?.id else selectedId,
                    isCoding = isCoding,
                    sidebarVisible = sidebarVisible,
                    onToggleSidebar = {
                        if (isChat) onChatSidebarVisibleChange(!chatSidebarVisible)
                        else onPrimarySidebarVisibleChange(!primarySidebarVisible)
                    },
                )
            }
            CompositionLocalProvider(LocalPaperDialogLifecycle provides null) {
            slot.child?.configuration?.let { dialog ->
                when (dialog.kind) {
                    "chat-model" -> {
                        val resolved = ProfileResolver.resolve(chats.current, chats.settings, chats.availableLlmProfiles)
                        val selection = chats.current?.modelSelection ?: resolved?.let { ModelSelection(it.id, it.selectionKey, it.effortSelectionFor()) }
                        FavoriteModelPicker(chats.modelPickerProfiles, selection,
                            { chat.selectChatModel(it); root.dismissDialog() }, root::dismissDialog, "Модель чата", footer = {
                                PaperAction({ root.dismissDialog(); root.navigate(AppRoute.Settings(SettingsSection.MODELS)) }) { PaperText("Настроить модели") }
                            })
                    }
                    // A feature modal is drawn by its owner; the slot entry only serves back navigation.
                    RootDialogLifecycle.FEATURE_MODAL_KIND -> Unit
                    else -> {
                        val contribution = contributions.dialogs.firstOrNull { it.kind == dialog.kind }
                        if (contribution != null) contribution.Content(dialog, root)
                        else {
                            LaunchedEffect(dialog.kind) {
                                AppLog.error("shell", "dialog.unavailable", mapOf("kind" to dialog.kind))
                            }
                            PaperDialog("Раздел недоступен", { root.dismissDialog(dialog) },
                                confirmLabel = "Закрыть", onConfirm = { root.dismissDialog(dialog) }) {
                                PaperText("Этот раздел недоступен на этом устройстве.")
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun TopBar(
    root: RootComponent<AppChild>,
    usage: UsageLedger,
    selectedId: String?,
    isCoding: Boolean,
    sidebarVisible: Boolean,
    onToggleSidebar: () -> Unit,
) {
    // Keep both the controls and their subscriptions out of the fullscreen composition.
    if ((LocalWindowToolbarHeight.current ?: 56.dp) <= 0.dp) return
    val navigation by root.navigationState.collectAsState()
    val chrome = LocalWindowChrome.current
    PaperAppTitleBar(sidebarVisible, onToggleSidebar) {
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
