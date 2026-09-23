package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.data.coding.DesktopProjectDirPicker
import io.aequicor.magicpaper.data.computer.DesktopComputerUse
import io.aequicor.magicpaper.data.planning.GitPlanningWorkspace
import io.aequicor.magicpaper.data.research.ResearchSessionIntegrationChecks
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.CodingService
import io.aequicor.magicpaper.ui.nativeAppContributions
import io.aequicor.magicpaper.ui.NativeSettingsComponentFactory
import io.aequicor.magicpaper.ui.nativeSettingsContributions
import io.aequicor.magicpaper.ui.components.DefaultSubscriptionAccountPresentation

actual fun createMagicPaperRuntime(navigationSession: NavigationSessionConfig): MagicPaperRuntime {
    val persistence = desktopPersistenceStores(journalId = navigationSession.journalKey,
        fallbackJournalId = navigationSession.restoreFromKey)
    val store = FileKeyValueStore()
    val checks = io.aequicor.magicpaper.data.checks.createCommandChecks(persistence.events, store)
    val gitWorkspaces = io.aequicor.magicpaper.data.planning.GitWorkspaceAuthority(checks)
    val computer = DesktopComputerUse(persistence.events)
    val questionnaires = DefaultRuntimeQuestionnaireFactory(persistence.events)
    val skills = DesktopSkills(draftRepository = persistence.drafts)
    val native = createDesktopNativeRuntime(appJson, persistence.events, computer, persistence.secrets, questionnaires,
        skills::selection, skills::recordRun, skills.runObserver,
        browser = io.aequicor.magicpaper.data.browser.createDesktopBrowserSessions(persistence.events), checks = checks)
    val taskWorkspace = io.aequicor.magicpaper.data.planning.GitTaskWorkspace(authority = gitWorkspaces)
    return buildRuntime(
        researchPageBrowser = io.aequicor.magicpaper.data.browser.DesktopResearchPageBrowser(),
        // Пределы моделей из каталога установленного движка: эндпоинты без метаданных
        // (например, DashScope compatible-mode) иначе остаются на значении конфигурации.
        modelLimits = native.modelLimits,
        store = store,
        persistence = persistence,
        questionnaireFactory = questionnaires,
        mediaStore = if (System.getProperty("os.name").let { it.startsWith("Mac", true) || it.startsWith("Windows", true) }) FileMediaStore() else UnavailableMediaStore,
        navigationSession = navigationSession,
        projectSkills = skills.projectSkills,
        bridge = DesktopProfileBridge(),
        filePicker = DesktopFilePicker(),
        openAiSubscription = native.subscription,
        // Only this host installs an agent's executable bindings and lifecycle owner.
        platformDefinitions = { scope -> nativeRuntimeBindings(scope, native.runtime, DesktopProjectDirPicker(),
            GitPlanningWorkspace(authority = gitWorkspaces), taskWorkspace,
            ResearchSessionIntegrationChecks(checks)) },
        runtimeExtensions = { listOf(NativeRuntimeExtension(get(), computer, native::prepareForReset, native::resumeAfterReset,
            discardUnresolvableChecks = { checks.discardUnresolvable() },
            eraseFiles = { completeRuntimeCleanup({ taskWorkspace.eraseForReset() }, { native.eraseSessionsForReset() }) })) },
        mediaOwnerPolicies = { listOf(NativeMediaOwnerPolicy(get())) },
        featurePlugins = { get<CodingFeature>().plugins },
        appContributions = { nativeAppContributions(get(), get()) },
        settingsRuntime = {
            val coding = { get<CodingService>() }
            object : SettingsRuntimeParticipant {
                override suspend fun prepare(previous: AppSettings, next: AppSettings) = coding().prepareSettings(previous, next)
                override suspend fun apply(settings: AppSettings) { coding().applySettings(settings).getOrThrow() }
            }
        },
        responseExtensions = { listOf(LayoutChatExtension(
            LayoutChatAgent(get(), io.aequicor.magicpaper.data.layout.DesktopLayoutEditor()),
            project = { boundId ->
                val coding = get<CodingService>().state.value.coding
                if (boundId == null) coding.current ?: coding.projects.singleOrNull()
                else coding.projects.firstOrNull { it.id == boundId }
            })) },
        settingsContributions = { nativeSettingsContributions(NativeSettingsComponentFactory(get(), get(), computer.permissions,
            DefaultSubscriptionAccountPresentation)) },
        platformPlugins = listOf(skills.plugin),
        packageInstructions = skills.instructions,
        experiencePlugin = skills::experiencePlugin,
        onPlatformStarted = skills::start,
        onPlatformClosed = {
            var failure: Throwable? = null
            try { skills.close() } catch (error: Throwable) { failure = error }
            try { native.shutdown() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            try { computer.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            failure?.let { throw it }
        },
    )
}
