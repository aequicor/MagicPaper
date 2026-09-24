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
    /** Как движок показывает уровень, если имя в его интерфейсе не совпадает со значением (Claude: `xhigh` — «extra»). */
    val levelNames: Map<String, String> = emptyMap(),
) {
    val supportsLevels: Boolean get() = levels.isNotEmpty()

    /** Имя уровня в интерфейсе движка; движку при этом уходит сам [level]. */
    fun levelName(level: String): String = levelNames[level] ?: level

    /**
     * Подпись уровня для чипа и диалога: выбранный уровень либо реальное умолчание движка
     * («по умолчанию: medium»), а не безликое `default`. `null` — у модели нет thinking.
     */
    fun levelLabel(level: String?): String? = when {
        !supportsLevels -> null
        level != null -> levelName(level)
        defaultLevel != null -> "по умолчанию: ${levelName(defaultLevel)}"
        else -> "по умолчанию"
    }
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

/** Уровень выбора в шкале приложения. Только для показа: движку уходит строка выбора. */
fun CodingModelSelection.displayEffort(): EffortSelection =
    level?.let(ReasoningEffort::fromWire)?.let(EffortSelection::of) ?: EffortSelection.Default

/**
 * Подключение, через которое движок запускает модели своего каталога: у Codex это подписка ChatGPT,
 * у Claude Code — профиль Anthropic (ключ либо собственный вход движка). У pi каталога нет.
 */
fun LlmProfile.isNativeConnectionFor(engine: CodingEngine): Boolean = operational && when (engine) {
    CodingEngine.CODEX -> provider == ProviderType.OPENAI_SUBSCRIPTION
    CodingEngine.CLAUDE_CODE -> provider == ProviderType.ANTHROPIC || provider == ProviderType.ANTHROPIC_SUBSCRIPTION
    CodingEngine.PI -> false
}

/**
 * Подключение, которое движок держит сам: он входит в аккаунт своими средствами, и профиль приложения
 * для запуска не нужен. Значение не сохраняется в списке профилей и не несёт секретов; `null` — движку
 * нужен профиль.
 */
fun CodingEngine.ownConnection(): LlmProfile? = when (this) {
    CodingEngine.CLAUDE_CODE -> LlmProfile("engine:claude-code", title, "https://api.anthropic.com",
        provider = ProviderType.ANTHROPIC, authType = LlmAuthType.X_API_KEY)
    CodingEngine.PI, CodingEngine.CODEX -> null
}

/**
 * Подключение движка: профиль приложения ([preferredId] в приоритете), а без него — собственное
 * подключение движка. [preferredId] собственного подключения выбирает его же.
 */
fun List<LlmProfile>.nativeConnectionFor(engine: CodingEngine, preferredId: String? = null): LlmProfile? {
    val connections = filter { it.isNativeConnectionFor(engine) }
    return connections.firstOrNull { it.id == preferredId } ?: connections.firstOrNull() ?: engine.ownConnection()
}

/**
 * Уровень модели, ближайший к желаемому по шкале приложения; при равенстве берётся меньший.
 * Это выбор среди объявленных уровней при рекомендации, а не подмена явного выбора пользователя.
 * `null` — у модели нет распознаваемых уровней, и поле уровня лучше не задавать.
 */
fun CodingModel.nearestLevel(desired: ReasoningEffort): String? {
    val target = ReasoningEffort.rank(desired)
    return levels.mapNotNull { name -> ReasoningEffort.fromWire(name)?.let { name to ReasoningEffort.rank(it) } }
        .minWithOrNull(compareBy({ kotlin.math.abs(it.second - target) }, { it.second }))?.first
}

/**
 * Состав планировщика для движка с нативным каталогом: у подключения движка вместо избранного
 * профиля стоят модели каталога, остальные подключения не меняются.
 */
fun List<LlmProfile>.withNativeCatalog(snapshot: CodingModelSnapshot): List<LlmProfile> = map { profile ->
    if (profile.isNativeConnectionFor(snapshot.engine)) profile.copy(favoriteModels = snapshot.models.map { it.id }, variants = emptyList())
    else profile
}

/** Назначение этапа на модель из каталога движка; [level] `null` — умолчание движка. */
fun nativeStageAssignment(connection: LlmProfile, engine: CodingEngine, model: CodingModel, level: String?,
    explanation: String = "", manual: Boolean = false): StageAssignment {
    val choice = CodingModelSelection(engine, model.provider, model.id, level)
    return StageAssignment(connection.id, model.id, choice.displayEffort(), choice.displayEffort(), explanation, manual,
        model.name, native = choice)
}
