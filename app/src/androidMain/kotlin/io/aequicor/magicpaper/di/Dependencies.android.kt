package io.aequicor.magicpaper.di

import android.content.Context
import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.data.coding.NoopProjectDirPicker
import io.aequicor.magicpaper.data.storage.AndroidKeyValueStore
import io.aequicor.magicpaper.domain.AndroidProfileBridge

/** Контекст задаётся из MainActivity до первого использования зависимостей. */
object AndroidEnv {
    lateinit var context: Context
}

/** Android: кодинг-бэкенд пока недоступен (заглушка), раздел проектов показывается честно. */
actual fun createMagicPaperRuntime(navigationSession: NavigationSessionConfig): MagicPaperRuntime {
    val store = AndroidKeyValueStore(AndroidEnv.context)
    return buildRuntime(
        store = store,
        persistence = io.aequicor.magicpaper.data.storage.androidPersistenceStores(AndroidEnv.context, navigationSession.journalKey, navigationSession.restoreFromKey),
        navigationSession = navigationSession,
        bridge = AndroidProfileBridge(AndroidEnv.context),
        codingRuntime = NoopCodingRuntime,
        dirPicker = NoopProjectDirPicker,
    )
}
