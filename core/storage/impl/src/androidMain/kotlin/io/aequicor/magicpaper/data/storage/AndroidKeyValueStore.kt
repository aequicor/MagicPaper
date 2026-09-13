package io.aequicor.magicpaper.data.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * Android-хранилище поверх SharedPreferences: файлы приложения,
 * удаляются вместе с приложением.
 */
class AndroidKeyValueStore(context: Context) : KeyValueStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("magicpaper", Context.MODE_PRIVATE)

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun keys(prefix: String): List<String> =
        prefs.all.keys.filter { it.startsWith(prefix) }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    override val description: String get() = "SharedPreferences приложения"
}
