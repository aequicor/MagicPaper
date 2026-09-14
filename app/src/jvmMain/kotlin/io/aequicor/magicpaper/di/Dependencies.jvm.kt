package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.data.coding.DesktopProjectDirPicker
import io.aequicor.magicpaper.data.computer.DesktopComputerUse
import io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription
import io.aequicor.magicpaper.data.planning.GitPlanningWorkspace
import io.aequicor.magicpaper.data.research.ResearchSessionIntegrationChecks
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.DesktopProfileBridge

actual fun createMagicPaperRuntime(navigationSession: NavigationSessionConfig): MagicPaperRuntime {
    val persistence = desktopPersistenceStores(journalId = navigationSession.journalKey,
        fallbackJournalId = navigationSession.restoreFromKey)
    val computer = DesktopComputerUse()
    val subscription = CodexAppServerOpenAiSubscription(appJson, computerUse = computer, secretStore = persistence.secrets)
    val skills = DesktopSkills(draftRepository = persistence.drafts)
    val engine = createDesktopCodingRuntime(computer, subscription, skills::selection, skills::recordRun, skills.runObserver)
    return buildRuntime(
        layoutEditor = io.aequicor.magicpaper.data.layout.DesktopLayoutEditor(),
        store = FileKeyValueStore(),
        persistence = persistence,
        navigationSession = navigationSession,
        projectSkills = skills.projectSkills,
        bridge = DesktopProfileBridge(),
        codingRuntime = engine,
        dirPicker = DesktopProjectDirPicker(),
        filePicker = DesktopFilePicker(),
        openAiSubscription = subscription,
        planningWorkspace = GitPlanningWorkspace(),
        taskWorkspace = io.aequicor.magicpaper.data.planning.GitTaskWorkspace(),
        integrationChecks = ResearchSessionIntegrationChecks(),
        platformPlugins = listOf(skills.plugin),
        packageInstructions = skills.instructions,
        experiencePlugin = skills::experiencePlugin,
        onPlatformStarted = skills::start,
        onPlatformClosed = {
            try { skills.close() }
            finally { try { engine.abortAll() } finally { subscription.close() } }
        },
    )
}
