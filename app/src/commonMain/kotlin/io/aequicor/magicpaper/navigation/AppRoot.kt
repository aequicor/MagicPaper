package io.aequicor.magicpaper.navigation

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperButton
import io.aequicor.magicpaper.logging.AppLog
import com.arkivanov.decompose.router.slot.SlotNavigation
import com.arkivanov.decompose.router.slot.childSlot
import com.arkivanov.decompose.router.slot.activate
import com.arkivanov.decompose.router.slot.dismiss
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.childContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import io.aequicor.magicpaper.data.storage.PersistenceStores
import io.aequicor.magicpaper.di.*
import io.aequicor.magicpaper.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** The root owns composition only. Feature work belongs to the application services. */
class AppChild(private val context: ComponentContext, private val runtime: MagicPaperRuntime,
    presentation: String? = null, onPresentation: (String) -> Unit = {},
    private val onPresentationError: (String) -> Unit = {},
    private val create: (ComponentContext) -> (@Composable () -> Unit),
) {
    private val presentationState = VisitPresentationState(presentation, onPresentationError, onPresentation)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val renderer = MutableStateFlow<(@Composable () -> Unit)?>(null)
    private val failure = MutableStateFlow<String?>(null)
    internal val creationFailure = failure.asStateFlow()
    private var creation: Job? = null
    private var attempt = 0
    private var destroyed = false
    private data class Creation(val renderer: (@Composable () -> Unit)?, val error: Exception?)
    private val creationNavigation = SlotNavigation<Int>()
    private val creations = context.childSlot(
        source = creationNavigation, serializer = null, key = "screen-creation", handleBackButton = false,
        childFactory = { _, attemptContext ->
            try { Creation(create(attemptContext), null) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Creation(null, error) }
        },
    )
    init {
        context.lifecycle.doOnDestroy {
            destroyed = true
            try { presentationState.flush() } finally { scope.cancel() }
        }
        retry()
    }
    fun retry() {
        if (destroyed || renderer.value != null || creation?.isActive == true) return
        failure.value = null
        creation = scope.launch {
            runtime.ready.first { it == RuntimeState.Ready }
            // Decompose owns attempt destruction; childContext lifecycles cannot
            // be destroyed manually. Dismiss a partial factory result before retry.
            creationNavigation.activate(++attempt)
            val result = checkNotNull(creations.value.child).instance
            val error = result.error
            if (error == null) renderer.value = checkNotNull(result.renderer)
            else {
                AppLog.error("screen", "creation.failed", error, mapOf("attempt" to attempt.toString()))
                try { creationNavigation.dismiss() }
                catch (cleanup: Exception) {
                    if (cleanup is CancellationException) throw cleanup
                    AppLog.error("screen", "creation.cleanup.failed", cleanup, mapOf("attempt" to attempt.toString()))
                }
                failure.value = "Не удалось открыть раздел. Повторите попытку."
                onPresentationError(requireNotNull(failure.value))
            }
        }
    }
    @Composable fun Content() {
        val content by renderer.collectAsState()
        val error by failure.collectAsState()
        presentationState.Content {
            when {
                error != null -> Column {
                    PaperText(requireNotNull(error))
                    PaperButton(label = "Повторить", onClick = ::retry)
                }
                content != null -> requireNotNull(content).invoke()
                else -> PaperText("Загрузка…")
            }
        }
    }
    fun flushPresentation() = presentationState.flush()
}

fun createAppRoot(runtime: MagicPaperRuntime, componentContext: ComponentContext): RootComponent<AppChild> {
    val koin = runtime.koin
    val events = koin.get<NavigationEvents>()
    var rootReference: RootComponent<AppChild>? = null
    val pendingRootActions = mutableListOf<(RootComponent<AppChild>) -> Unit>()
    fun withRoot(action: (RootComponent<AppChild>) -> Unit) {
        val current = rootReference
        if (current == null) pendingRootActions += action else action(current)
    }
    val rootScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error ->
        AppLog.error("root", "subscription.failed", error)
        withRoot { it.reportNavigationError("Не удалось обновить экран. Повторите открытие раздела.") }
    })
    componentContext.lifecycle.doOnDestroy { rootScope.cancel() }
    lateinit var root: RootComponent<AppChild>
    root = DefaultRootComponent(componentContext, ReferencedNavigationSnapshotStore(koin.get<PersistenceStores>().navigation),
        factory = FeatureComponentFactory { visit, context, presentation ->
            AppChild(context, runtime, presentationEntry(presentation, "compose"), { snapshot -> withRoot { it.savePresentationEntry(visit.id, "compose", snapshot) } }, { message -> withRoot { it.reportNavigationError(message) } }) { attemptContext ->
                when (val route = visit.route) {
                    is AppRoute.Chat -> {
                        val component = koin.get<ChatComponent.Factory>(FeatureFactoryQualifiers.chat).create(attemptContext, ChatInput(route.sessionId)) {
                            when (it) {
                                ChatOutput.Models -> events.navigate(AppRoute.Settings(SettingsSection.MODELS))
                                ChatOutput.ModelSwitcher -> events.dialog(DialogRoute("chat-model", route.sessionId))
                            }
                        }
                        val render: @Composable () -> Unit = { component.Content() }
                        render
                    }
                    is AppRoute.Projects -> {
                        val contribution = koin.get<AppContributions>().routes.firstOrNull { it.kind == route.logKind() }
                        val component = contribution?.create(attemptContext, route, events)
                        val render: @Composable () -> Unit = {
                            if (component != null) component.Content()
                            else io.aequicor.magicpaper.ui.screens.UnavailableAppSection { events.navigate(AppRoute.Chat()) }
                        }
                        render
                    }
                    is AppRoute.Settings -> {
                        val page = when (route.section) {
                            SettingsSection.OVERVIEW -> SettingsPage.OVERVIEW
                            SettingsSection.MODELS -> SettingsPage.MODELS
                            SettingsSection.ENGINES -> SettingsPage.ENGINES
                            SettingsSection.COMPUTER -> SettingsPage.COMPUTER
                            SettingsSection.PROFILE -> SettingsPage.PROFILE
                        }
                        val component = koin.get<SettingsComponent.Factory>(FeatureFactoryQualifiers.settings).create(attemptContext, SettingsInput(page, route.profileId), events::settingsOutput)
                        val render: @Composable () -> Unit = { component.Content() }
                        render
                    }
                    is AppRoute.Docs -> {
                        val component = koin.get<DocsComponent.Factory>(FeatureFactoryQualifiers.docs).create(attemptContext, DocsInput(route.articleId, query = presentationEntry(presentation, "docs-query").orEmpty())) {
                            when (it) {
                                is DocsOutput.OpenArticle -> events.navigate(AppRoute.Docs(it.id))
                                is DocsOutput.QueryChanged -> root.savePresentationEntry(visit.id, "docs-query", it.query)
                                DocsOutput.Overview -> events.navigate(AppRoute.Docs())
                            }
                        }
                        val render: @Composable () -> Unit = { component.Content() }
                        render
                    }
                    is AppRoute.Plugins -> {
                        if (route.pluginId in setOf("skill-shop", "local-skill-packages", "self-education")) {
                            val component = koin.get<SkillsComponent.Factory>(FeatureFactoryQualifiers.skills).create(attemptContext, SkillsInput(requireNotNull(route.pluginId))) {
                                when (it) { is SkillsOutput.OpenPlugin -> events.navigate(AppRoute.Plugins(it.id)) }
                            }
                            val render: @Composable () -> Unit = { component.Content() }
                            render
                        } else {
                            val component = koin.get<PluginsComponent.Factory>(FeatureFactoryQualifiers.plugins).create(attemptContext, PluginsInput(route.pluginId)) {
                                when (it) {
                                    is PluginsOutput.OpenPlugin -> events.navigate(AppRoute.Plugins(it.id))
                                    PluginsOutput.Overview -> events.navigate(AppRoute.Plugins())
                                    PluginsOutput.Coding -> events.navigate(AppRoute.Projects())
                                }
                            }
                            val render: @Composable () -> Unit = { component.Content() }
                            render
                        }
                    }
                }
            }
        }, initialDeepLink = runtime.navigationSession.initialDeepLink)
    rootReference = root
    pendingRootActions.toList().forEach { it(root) }
    pendingRootActions.clear()
    rootScope.launch {
        runtime.ready.first { it == RuntimeState.Ready }
        koin.get<SettingsService>().state.collect { root.setWelcomeRequired(it.showWelcome) }
    }
    rootScope.launch {
        events.events.collect { event ->
            when (event) {
                is NavigationEvents.Event.Navigate -> root.navigate(event.route)
                is NavigationEvents.Event.Dialog -> root.showDialog(event.route)
                NavigationEvents.Event.Back -> root.back()
                is NavigationEvents.Event.Reset -> {
                    // A neutral destination owns no chat/form drafts while persistence resets.
                    root.reset(AppRoute.Docs())
                    root.awaitIdle()
                    event.completed.complete(Unit)
                }
                is NavigationEvents.Event.ResetComplete -> {
                    root.reset(AppRoute.Chat())
                    root.awaitIdle()
                    event.completed.complete(Unit)
                }
            }
        }
    }
    val welcomeContext = componentContext.childContext("welcome")
    val welcome = AppChild(welcomeContext, runtime) { attemptContext ->
        val component = koin.get<SettingsComponent.Factory>(FeatureFactoryQualifiers.settings).create(attemptContext, SettingsInput(SettingsPage.WELCOME), events::settingsOutput)
        val render: @Composable () -> Unit = { component.Content() }
        render
    }
    return ApplicationRoot(root, welcome)
}

class ApplicationRoot(private val delegate: RootComponent<AppChild>, val welcome: AppChild) : RootComponent<AppChild> by delegate {
    var shellPresentation: VisitPresentationState? = null
    override suspend fun awaitIdle(): Unit = withContext(Dispatchers.Main.immediate) {
        shellPresentation?.flush()
        (stack.value.backStack + stack.value.active).forEach { it.instance.flushPresentation() }
        welcome.flushPresentation()
        delegate.awaitIdle()
    }
}

internal fun NavigationEvents.settingsOutput(output: SettingsOutput) {
    when (output) {
        SettingsOutput.Overview -> navigate(AppRoute.Settings())
        SettingsOutput.Back -> back()
        SettingsOutput.Chat -> navigate(AppRoute.Chat())
        SettingsOutput.Projects -> navigate(AppRoute.Projects())
        SettingsOutput.Plugins -> navigate(AppRoute.Plugins())
        SettingsOutput.Docs -> navigate(AppRoute.Docs())
        SettingsOutput.Models -> navigate(AppRoute.Settings(SettingsSection.MODELS))
        SettingsOutput.Engines -> navigate(AppRoute.Settings(SettingsSection.ENGINES))
        SettingsOutput.Computer -> navigate(AppRoute.Settings(SettingsSection.COMPUTER))
        is SettingsOutput.Profile -> navigate(AppRoute.Settings(SettingsSection.PROFILE, output.id))
    }
}
