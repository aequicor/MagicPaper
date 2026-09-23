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

/** A store that reads several keys in one batch, returning for each exactly what [read] would. */
interface BatchReadKeyValueStore : KeyValueStore {
    fun readBatch(keys: Collection<String>): Map<String, String?>
}

/**
 * The values [KeyValueStore.read] returns for keys a caller needs together, such as the payloads a
 * journal replays. Only a store that declares batch reads is read as a batch: a wrapper decorating
 * [KeyValueStore.read] (a fault-injecting test store, for one) keeps being read key by key through it,
 * which a default interface method forwarded by `by` delegation would silently bypass.
 */
fun KeyValueStore.readAll(keys: Collection<String>): Map<String, String?> =
    if (this is BatchReadKeyValueStore) readBatch(keys) else keys.associateWith(::read)

/** Тестовая/аварийная реализация в памяти. */
class InMemoryKeyValueStore : KeyValueStore {
    val secrets: SecretStore = InMemorySecretStore()
    private val map = LinkedHashMap<String, String>()
    override fun read(key: String): String? = map[key]
    override fun write(key: String, value: String) { map[key] = value }
    override fun delete(key: String) { map.remove(key) }
    override fun keys(prefix: String): List<String> = map.keys.filter { it.startsWith(prefix) }
    override fun clear() { map.clear() }
    override val description: String get() = "in-memory"
}
