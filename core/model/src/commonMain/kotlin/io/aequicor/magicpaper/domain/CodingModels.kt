package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

/**
 * Модель, как её объявляет сам движок кодинг-сессий (Pi, Codex). Приложение её не
 * достраивает эвристикой [ModelDefaults]: уровни thinking, значение «по умолчанию» и лимиты
 * берутся из каталога движка как есть.
 *
 * Уровни хранятся строками в словаре движка, а не [ReasoningEffort]: у движка набор шире и
 * меняется вместе с его версией, а закрытая шкала приложения молча подменяла бы уровни
 * ближайшими.
 */
@Serializable
data class CodingModel(
    /** Идентификатор провайдера в словаре движка (`qwen-token-plan`, `openai`). */
    val provider: String,
    val id: String,
    val name: String = id,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    /** Нативные уровни в порядке движка; пусто — модель не поддерживает thinking. */
    val levels: List<String> = emptyList(),
    /** Уровень, который движок применяет, когда пользователь ничего не выбрал; null — движок его не объявил. */
    val defaultLevel: String? = null,
    val acceptsImages: Boolean = false,
) {
    val supportsLevels: Boolean get() = levels.isNotEmpty()
}

/**
 * Выбор модели для кодинг-сессии. [level] `null` — «по умолчанию»: конкретное значение
 * определяет [CodingModel.defaultLevel] движка, а не константа приложения.
 */
@Serializable
data class CodingModelSelection(
    val engine: CodingEngine,
    val provider: String,
    val modelId: String,
    val level: String? = null,
)

/** Каталог одного движка на момент [refreshedAt] (миллисекунды эпохи). */
@Serializable
data class CodingModelSnapshot(
    val engine: CodingEngine,
    val models: List<CodingModel>,
    val refreshedAt: Long,
) {
    fun find(provider: String, modelId: String): CodingModel? =
        models.firstOrNull { it.provider == provider && it.id == modelId }

    /**
     * Сверка сохранённого выбора со снимком. Ничего не подменяется молча: пропавшая модель и
     * неподдерживаемый уровень возвращаются явными исходами, решение принимает вызывающий.
     */
    fun resolve(selection: CodingModelSelection): CodingModelResolution {
        if (selection.engine != engine) return CodingModelResolution.WrongEngine(engine)
        val model = find(selection.provider, selection.modelId) ?: return CodingModelResolution.ModelMissing
        val requested = selection.level
        if (requested != null && requested !in model.levels) {
            return CodingModelResolution.LevelUnsupported(model, requested)
        }
        return CodingModelResolution.Available(model, effectiveLevel = requested ?: model.defaultLevel)
    }
}

sealed interface CodingModelResolution {
    /** [effectiveLevel] — то, что реально попадёт движку; null — уровень не задаётся вовсе. */
    data class Available(val model: CodingModel, val effectiveLevel: String?) : CodingModelResolution
    data object ModelMissing : CodingModelResolution
    data class LevelUnsupported(val model: CodingModel, val level: String) : CodingModelResolution
    data class WrongEngine(val snapshotEngine: CodingEngine) : CodingModelResolution
}
