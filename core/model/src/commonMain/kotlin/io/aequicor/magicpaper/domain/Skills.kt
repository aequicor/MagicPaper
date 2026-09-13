package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

/** Происхождение навыка. */
@Serializable
enum class SkillSource {
    /** Установлен из лавки (каталога проверенных навыков). */
    CATALOG,

    /** Создан агентом из диалога или вручную (самонастройка). */
    SELF_MADE,
}

/**
 * Установленный навык агента: «когда применять» + инструкция.
 * Подбор идёт по [description] (прогрессивное раскрытие: селектор читает
 * описание, а в контекст агента попадает полный текст).
 */
@Serializable
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    val tags: List<String> = emptyList(),
    val source: SkillSource = SkillSource.CATALOG,
    val enabled: Boolean = true,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    /** Ключ для проверки конфликтов имён (без учёта регистра). */
    val nameKey: String get() = name.trim().lowercase()
}

/** Позиция каталога (лавки) — проверенный навык, готовый к установке. */
@Serializable
data class CatalogEntry(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    val tags: List<String> = emptyList(),
) {
    val nameKey: String get() = name.trim().lowercase()
}

/** Черновик навыка, ожидающий подтверждения пользователя. */
@Serializable
data class SkillDraft(
    val name: String,
    val description: String,
    val instructions: String,
    /** Пояснение, как появился черновик (например, «без модели»). */
    val note: String? = null,
)

/** Хранилище установленных навыков. */


/** Магазин навыков: каталог проверенных временем позиций. */


/** Подбор навыков, релевантных запросу, для инъекции в контекст агента. */


/** Пустая библиотека — для сборок без системы навыков. */
