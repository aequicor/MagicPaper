package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.data.storage.FileKeyValueStore
import io.aequicor.magicpaper.domain.DesktopProfileBridge

actual fun createMagicPaperDependencies(): MagicPaperDependencies =
    buildDependencies(FileKeyValueStore(), DesktopProfileBridge())
