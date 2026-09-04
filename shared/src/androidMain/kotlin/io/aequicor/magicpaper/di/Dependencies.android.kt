package io.aequicor.magicpaper.di

import android.content.Context
import io.aequicor.magicpaper.data.storage.AndroidKeyValueStore
import io.aequicor.magicpaper.domain.AndroidProfileBridge

/** Контекст задаётся из MainActivity до первого использования зависимостей. */
object AndroidEnv {
    lateinit var context: Context
}

actual fun createMagicPaperDependencies(): MagicPaperDependencies =
    buildDependencies(AndroidKeyValueStore(AndroidEnv.context), AndroidProfileBridge(AndroidEnv.context))
