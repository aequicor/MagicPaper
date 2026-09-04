package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.data.coding.DesktopProjectDirPicker
import io.aequicor.magicpaper.data.coding.PiCodingRuntime
import io.aequicor.magicpaper.data.storage.FileKeyValueStore
import io.aequicor.magicpaper.domain.DesktopProfileBridge

actual fun createMagicPaperDependencies(): MagicPaperDependencies {
    val store = FileKeyValueStore()
    return buildDependencies(
        store = store,
        bridge = DesktopProfileBridge(),
        codingRuntime = PiCodingRuntime(),
        codingProjects = codingProjectRepository(store, appJson),
        dirPicker = DesktopProjectDirPicker(),
    )
}
