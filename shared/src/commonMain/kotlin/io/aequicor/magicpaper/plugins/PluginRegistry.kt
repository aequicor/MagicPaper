package io.aequicor.magicpaper.plugins

import androidx.compose.runtime.Composable

/**
 * Контракт плагина (SPI). Плагин — независимая фича с собственной панелью,
 * которую можно включить/выключить; интерфейс приложения расширяется
 * суммой включённых плагинов (Open/Closed).
 */
interface MagicPlugin {
    /** Уникальный стабильный идентификатор. */
    val id: String

    /** Название для экрана плагинов. */
    val title: String

    /** Короткое описание для экрана плагинов. */
    val description: String

    /** Иконка-эмодзи (без ресурсов — минимализм). */
    val icon: String

    /** Тело плагина: панель, встраиваемая в приложение. */
    @Composable
    fun Content()
}

/** Реестр плагинов. Точка расширения: добавление плагина = запись в список. */
class PluginRegistry {
    private val plugins = LinkedHashMap<String, MagicPlugin>()

    fun register(plugin: MagicPlugin): PluginRegistry {
        plugins[plugin.id] = plugin
        return this
    }

    fun all(): List<MagicPlugin> = plugins.values.toList()
    fun byId(id: String): MagicPlugin? = plugins[id]
}
