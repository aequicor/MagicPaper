package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.data.storage.BrowserFilePicker
import io.aequicor.magicpaper.data.storage.BrowserKeyValueStore
import io.aequicor.magicpaper.domain.BrowserProfileBridge

/** Browser host has no native execution registration. */
actual fun createMagicPaperRuntime(navigationSession: NavigationSessionConfig): MagicPaperRuntime {
    val store = BrowserKeyValueStore()
    return buildRuntime(
        store = store,
        persistence = io.aequicor.magicpaper.data.storage.browserPersistenceStores(journalId = navigationSession.journalKey, fallbackJournalId = navigationSession.restoreFromKey),
        navigationSession = navigationSession,
        bridge = BrowserProfileBridge(),
        filePicker = BrowserFilePicker(),
    )
}
