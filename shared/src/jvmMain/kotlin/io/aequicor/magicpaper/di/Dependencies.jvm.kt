package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.data.coding.DesktopProjectDirPicker
import io.aequicor.magicpaper.data.coding.DesktopCodingRuntime
import io.aequicor.magicpaper.data.coding.PiCodingRuntime
import io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription
import io.aequicor.magicpaper.data.storage.DesktopFilePicker
import io.aequicor.magicpaper.data.storage.FileKeyValueStore
import io.aequicor.magicpaper.domain.DesktopProfileBridge

actual fun createMagicPaperDependencies(): MagicPaperDependencies {
    val store = FileKeyValueStore()
    val subscription = CodexAppServerOpenAiSubscription(appJson)
    val runtime = DesktopCodingRuntime(PiCodingRuntime(), subscription)
    val dependencies = buildDependencies(
        store = store,
        bridge = DesktopProfileBridge(),
        codingRuntime = runtime,
        codingProjects = codingProjectRepository(store, appJson),
        dirPicker = DesktopProjectDirPicker(),
        filePicker = DesktopFilePicker(),
        openAiSubscription = subscription,
        planningWorkspace = io.aequicor.magicpaper.data.planning.GitPlanningWorkspace(),
    )
    Runtime.getRuntime().addShutdownHook(Thread({
        try { kotlinx.coroutines.runBlocking { dependencies.planning.shutdown() } }
        finally { runtime.abortAll(); subscription.close() }
    }, "magicpaper-planning-shutdown"))
    return dependencies
}
