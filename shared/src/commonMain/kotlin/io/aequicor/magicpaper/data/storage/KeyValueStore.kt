package io.aequicor.magicpaper.data.storage

/**
 * Минималистичное key-value хранилище.
 * На десктопе — папка данных (изолированная среда, без прав администратора),
 * в браузере — localStorage с префиксом приложения.
 */
interface KeyValueStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun delete(key: String)
    fun keys(prefix: String): List<String>
    fun clear()
    val description: String
}

/** Тестовая/аварийная реализация в памяти. */
class InMemoryKeyValueStore : KeyValueStore {
    private val map = LinkedHashMap<String, String>()
    override fun read(key: String): String? = map[key]
    override fun write(key: String, value: String) { map[key] = value }
    override fun delete(key: String) { map.remove(key) }
    override fun keys(prefix: String): List<String> = map.keys.filter { it.startsWith(prefix) }
    override fun clear() { map.clear() }
    override val description: String get() = "in-memory"
}
