package io.aequicor.magicpaper.data.storage

import kotlinx.browser.localStorage

/**
 * Браузерное хранилище поверх localStorage с префиксом приложения.
 * Данные живут в профиле браузера и очищаются вместе с данными сайта.
 * Общий код для Kotlin/JS и Kotlin/Wasm через библиотеку kotlinx-browser.
 */
class BrowserKeyValueStore : KeyValueStore {

    override fun read(key: String): String? = localStorage.getItem(fullKey(key))

    override fun write(key: String, value: String) {
        localStorage.setItem(fullKey(key), value)
    }

    override fun delete(key: String) {
        localStorage.removeItem(fullKey(key))
    }

    override fun keys(prefix: String): List<String> {
        val result = mutableListOf<String>()
        for (i in 0 until localStorage.length) {
            val key = localStorage.key(i) ?: continue
            if (key.startsWith(PREFIX)) {
                val short = key.removePrefix(PREFIX)
                if (short.startsWith(prefix)) result += short
            }
        }
        return result
    }

    override fun clear() {
        keys("").forEach { localStorage.removeItem(fullKey(it)) }
    }

    override val description: String get() = "localStorage браузера"

    private fun fullKey(key: String) = PREFIX + key

    private companion object {
        const val PREFIX = "magicpaper:"
    }
}
