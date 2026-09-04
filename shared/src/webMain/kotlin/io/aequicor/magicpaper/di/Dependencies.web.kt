package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.data.storage.BrowserKeyValueStore
import io.aequicor.magicpaper.domain.BrowserProfileBridge

actual fun createMagicPaperDependencies(): MagicPaperDependencies =
    buildDependencies(BrowserKeyValueStore(), BrowserProfileBridge())
