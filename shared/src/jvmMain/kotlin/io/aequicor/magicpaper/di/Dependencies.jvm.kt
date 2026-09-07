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
    return buildDependencies(
        store = store,
        bridge = DesktopProfileBridge(),
        codingRuntime = DesktopCodingRuntime(PiCodingRuntime(), subscription),
        codingProjects = codingProjectRepository(store, appJson),
        dirPicker = DesktopProjectDirPicker(),
        filePicker = DesktopFilePicker(),
        openAiSubscription = subscription,
    )
}
