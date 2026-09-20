package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.data.coding.NoopProjectDirPicker
import io.aequicor.magicpaper.data.storage.BrowserFilePicker
import io.aequicor.magicpaper.data.storage.BrowserKeyValueStore
import io.aequicor.magicpaper.domain.BrowserProfileBridge

/** Веб: кодинг-бэкенд недоступен (заглушка), проекты и журнал — в хранилище браузера. */
actual fun createMagicPaperRuntime(navigationSession: NavigationSessionConfig): MagicPaperRuntime {
    val store = BrowserKeyValueStore()
    return buildRuntime(
        store = store,
        persistence = io.aequicor.magicpaper.data.storage.browserPersistenceStores(journalId = navigationSession.journalKey, fallbackJournalId = navigationSession.restoreFromKey),
        navigationSession = navigationSession,
        bridge = BrowserProfileBridge(),
        codingRuntime = NoopCodingRuntime,
        dirPicker = NoopProjectDirPicker,
        filePicker = BrowserFilePicker(),
    )
}
