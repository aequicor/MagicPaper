package io.aequicor.magicpaper.domain

import android.content.Context

/**
 * Android-мост профиля: экспорт пишет файл в папку приложения,
 * импорт через системный диалог — в минимальной версии недоступен.
 */
class AndroidProfileBridge(private val context: Context) : ProfileBridge {

    override val supportsFilePicker = false

    override suspend fun export(json: String): Boolean = runCatching {
        val file = java.io.File(context.filesDir, "magicpaper-profile.json")
        file.writeText(json)
        true
    }.getOrDefault(false)

    override suspend fun import(): String? = null
}
