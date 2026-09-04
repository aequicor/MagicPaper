package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.data.coding.NoopProjectDirPicker
import io.aequicor.magicpaper.data.storage.BrowserKeyValueStore
import io.aequicor.magicpaper.domain.BrowserProfileBridge

/** Веб: кодинг-бэкенд недоступен (заглушка), проекты и журнал — в хранилище браузера. */
actual fun createMagicPaperDependencies(): MagicPaperDependencies {
    val store = BrowserKeyValueStore()
    return buildDependencies(
        store = store,
        bridge = BrowserProfileBridge(),
        codingRuntime = NoopCodingRuntime,
        codingProjects = JsonCodingProjectRepository(store, appJson),
        dirPicker = NoopProjectDirPicker,
    )
}
