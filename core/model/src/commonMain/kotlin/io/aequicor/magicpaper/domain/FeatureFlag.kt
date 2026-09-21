package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

/**
 * Фича-флаги для постепенного включения экспериментальных возможностей.
 * Каждый флаг описывает одну способность; [FeatureFlagState] хранит включённые.
 *
 * Глобальные флаги живут в [AppSettings.featureFlags], per-session override —
 * в [CodingSession.featureFlags]. Пустой session override = наследует глобальные.
 */
enum class FeatureFlag(
    val title: String,
    val description: String,
) {
    AGENT_SPEED_BOOST(
        "Ускорение агента",
        "Оптимизации: кэш навыков, effort routing, prompt caching, increased maxTokens, " +
            "сокращённый research prompt, кэш tool definitions, стабильный prompt prefix, skill summary, " +
            "инструкция минимизации вызовов инструментов.",
    ),
    NATIVE_CODING_MODELS(
        "Модели из каталога движка",
        "Для сессий Codex и Claude Code модель и уровень рассуждения выбираются из каталога самого движка " +
            "без подмены значениями приложения.",
    ),
}

/** Состояние фича-флагов: множество включённых флагов. */
@Serializable
data class FeatureFlagState(val enabled: Set<FeatureFlag> = emptySet()) {
    fun isEnabled(flag: FeatureFlag): Boolean = flag in enabled

    fun with(flag: FeatureFlag, on: Boolean): FeatureFlagState =
        if (on) copy(enabled = enabled + flag) else copy(enabled = enabled - flag)

    companion object {
        val EMPTY = FeatureFlagState()
    }
}

/**
 * Per-session override: когда [override] не пустое, оно заменяет глобальные флаги.
 * Пустой [override] означает наследование от [AppSettings.featureFlags].
 */
@Serializable
data class FeatureFlagOverride(val override: FeatureFlagState? = null) {
    /** Разрешает флаги: session override, если задан, иначе глобальные [global]. */
    fun resolve(global: FeatureFlagState): FeatureFlagState = override ?: global

    fun with(flag: FeatureFlag, on: Boolean): FeatureFlagOverride =
        FeatureFlagOverride((override ?: FeatureFlagState()).with(flag, on))

    companion object {
        val INHERIT = FeatureFlagOverride()
    }
}
