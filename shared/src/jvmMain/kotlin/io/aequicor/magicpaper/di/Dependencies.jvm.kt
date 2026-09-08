package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.data.coding.DesktopProjectDirPicker
import io.aequicor.magicpaper.data.coding.DesktopCodingRuntime
import io.aequicor.magicpaper.data.coding.PiCodingRuntime
import io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription
import io.aequicor.magicpaper.data.storage.DesktopFilePicker
import io.aequicor.magicpaper.data.storage.FileKeyValueStore
import io.aequicor.magicpaper.domain.DesktopProfileBridge
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancel

actual fun createMagicPaperDependencies(): MagicPaperDependencies {
    val store = FileKeyValueStore()
    val subscription = CodexAppServerOpenAiSubscription(appJson)
    val runtime = DesktopCodingRuntime(PiCodingRuntime(), subscription)
    val skillPackages = io.aequicor.magicpaper.plugins.builtin.LocalSkillsPlugin(
        java.nio.file.Path.of(System.getProperty("user.home"), ".MagicPaper", "skill-packages"),
    )
    var experience: io.aequicor.magicpaper.data.skills.LocalSkillExperience? = null
    val experienceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    val dependencies = buildDependencies(
        store = store,
        bridge = DesktopProfileBridge(),
        codingRuntime = runtime,
        codingProjects = codingProjectRepository(store, appJson),
        dirPicker = DesktopProjectDirPicker(),
        filePicker = DesktopFilePicker(),
        openAiSubscription = subscription,
        planningWorkspace = io.aequicor.magicpaper.data.planning.GitPlanningWorkspace(),
        platformPlugins = listOf(skillPackages),
        packageInstructions = skillPackages.instructionSource,
        experiencePlugin = { gateway, profiles ->
            skillPackages.beforeInstructions = { checkNotNull(experience) { "Experience journal unavailable" }.retentionDays() }
            try {
                val journal = io.aequicor.magicpaper.data.skills.LocalSkillExperience(
                    java.nio.file.Path.of(System.getProperty("user.home"), ".MagicPaper", "skill-experience"),
                    skillPackages.repo(), gateway, { profiles.load().map { it.apiKey } },
                )
                experience = journal
                experienceScope.launch {
                    while (isActive) {
                        try { journal.search() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
                        kotlinx.coroutines.delay(60_000)
                    }
                }
                io.aequicor.magicpaper.plugins.builtin.LocalExperiencePlugin(journal, profiles)
            } catch (_: Exception) {
                io.aequicor.magicpaper.plugins.builtin.UnavailableExperiencePlugin
            }
        },
    )
    Runtime.getRuntime().addShutdownHook(Thread({
        try { kotlinx.coroutines.runBlocking { dependencies.planning.shutdown() } }
        finally { experienceScope.cancel(); experience?.close(); skillPackages.close(); runtime.abortAll(); subscription.close() }
    }, "magicpaper-planning-shutdown"))
    return dependencies
}
