package io.aequicor.magicpaper.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.abs

/**
 * Канонический уровень усилия модели. Порядок объявления — это шкала
 * интенсивности (по ней подбирается ближайший уровень при клампинге).
 *
 * Уровень — символ, а не число: провайдеры принимают только конечный набор
 * значений (`reasoning_effort`, `thinkingLevel`, бюджет токенов), поэтому
 * шкала 0–100 лишь изображала точность — 95 % её положений попадали в один и
 * тот же бакет.
 */
enum class ReasoningEffort(val wire: String, val label: String, val shortLabel: String) {
    NONE("none", "Выключено", "выкл"),
    MINIMAL("minimal", "Минимальное", "мин"),
    LOW("low", "Низкое", "низ"),
    MEDIUM("medium", "Среднее", "ср"),
    HIGH("high", "Высокое", "выс"),
    XHIGH("xhigh", "Очень высокое", "х-выс"),
    MAX("max", "Максимум", "макс"),

    /** «Реши сама» — режим, а не интенсивность: в шкалу не входит. */
    AUTO("auto", "Авто (по модели)", "авто"),
    ;

    companion object {

        /** Шкала интенсивности от слабого к сильному; AUTO — вне шкалы (это режим). */
        val LADDER: List<ReasoningEffort> = listOf(NONE, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX)

        /** Позиция уровня на шкале; для AUTO — позиция его смыслового аналога. */
        fun rank(effort: ReasoningEffort): Int = when (effort) {
            AUTO -> LADDER.indexOf(MEDIUM)
            else -> LADDER.indexOf(effort)
        }

        /** Уровень по провайдерскому имени, имени перечисления или легаси-алиасу. */
        fun fromWire(value: String): ReasoningEffort? {
            val key = value.trim().lowercase().replace(' ', '_').replace('-', '_')
            entries.forEach { effort ->
                if (effort.wire == key || effort.name.lowercase() == key) return effort
            }
            return when (key) {
                "off", "disabled" -> NONE
                "min", "minimum" -> MINIMAL
                "med", "mid", "middle" -> MEDIUM
                "x_high", "extrahigh", "extra_high" -> XHIGH
                "ultra", "maximum", "highest" -> MAX
                "dynamic" -> AUTO
                else -> null
            }
        }

        /**
         * Старая шкала 0–100 → уровень. Нужна только чтобы прочитать профили
         * прежних версий; в новом коде чисел усилия не бывает.
         */
        fun fromLegacyScale(value: Int): ReasoningEffort = when (value.coerceIn(0, 100)) {
            0 -> NONE
            in 1..10 -> MINIMAL
            in 11..39 -> LOW
            in 40..64 -> MEDIUM
            in 65..84 -> HIGH
            in 85..94 -> XHIGH
            else -> MAX
        }
    }
}

/**
 * Выбор пользователя: конкретный уровень или «по умолчанию провайдера»
 * ([Default]) — во втором случае поле в запрос не попадает вовсе.
 */
@Serializable(with = EffortSelection.Serializer::class)
data class EffortSelection private constructor(val level: ReasoningEffort?) {

    val isDefault: Boolean get() = level == null

    /** Подпись для чипов: короткое имя уровня либо «умолч». */
    val shortLabel: String get() = level?.shortLabel ?: "умолч"

    val label: String get() = level?.label ?: "По умолчанию провайдера"

    companion object {
        /** Не задавать усилие — пусть модель/провайдер решает сам. */
        val Default: EffortSelection = EffortSelection(null)

        fun of(level: ReasoningEffort): EffortSelection = EffortSelection(level)

        fun ofOrNull(level: ReasoningEffort?): EffortSelection = EffortSelection(level)
    }

    /**
     * Сериализатор с обратной совместимостью: исторически усилие хранили
     * строкой перечисления ("HIGH"), затем числом 0–100. Читаем всё, пишем
     * строку уровня либо "default".
     */
    internal object Serializer : KSerializer<EffortSelection> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("EffortSelection", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: EffortSelection) {
            val text = value.level?.wire ?: "default"
            if (encoder is JsonEncoder) {
                encoder.encodeJsonElement(JsonPrimitive(text))
            } else {
                encoder.encodeString(text)
            }
        }

        override fun deserialize(decoder: Decoder): EffortSelection {
            val content = readContent(decoder) ?: return Default
            ReasoningEffort.fromWire(content)?.let { return of(it) }
            content.toIntOrNull()?.let { return of(ReasoningEffort.fromLegacyScale(it)) }
            content.toDoubleOrNull()?.let { return of(ReasoningEffort.fromLegacyScale(it.toInt())) }
            return Default
        }

        private fun readContent(decoder: Decoder): String? {
            if (decoder is JsonDecoder) {
                val primitive = runCatching { decoder.decodeJsonElement() }.getOrNull() as? JsonPrimitive
                    ?: return null
                if (primitive is JsonNull) return null
                primitive.intOrNull?.let { return it.toString() }
                return primitive.content
            }
            return runCatching { decoder.decodeString() }.getOrNull()
        }
    }
}

/**
 * Привычные имена уровней усилия. Исторический мост: код и тесты знают их как
 * константы; значения — те же [EffortSelection], никаких чисел.
 */
object Effort {
    val OFF: EffortSelection = EffortSelection.of(ReasoningEffort.NONE)
    val LOW: EffortSelection = EffortSelection.of(ReasoningEffort.LOW)
    val MEDIUM: EffortSelection = EffortSelection.of(ReasoningEffort.MEDIUM)
    val HIGH: EffortSelection = EffortSelection.of(ReasoningEffort.HIGH)
    val ULTRA: EffortSelection = EffortSelection.of(ReasoningEffort.MAX)
}

/**
 * Как усилие кодируется в запросе. Диалект — факт о поколении API вендора и
 * он независим от того, какие уровни модель объявляет: claude-opus-4.5
 * принимает `effort`, но мыслится всё ещё бюджетом токенов.
 */
enum class WireDialect {
    /** `reasoning_effort` / `reasoning.effort` (OpenAI, OpenRouter, совместимые). */
    EFFORT,

    /** `thinking.type = adaptive` + `output_config.effort` (Claude 4.6+). */
    ADAPTIVE_EFFORT,

    /** `thinking.budget_tokens` (Claude ≤4.5), `thinkingConfig.thinkingBudget` (Gemini 2.5). */
    BUDGET_TOKENS,

    /** `thinkingConfig.thinkingLevel` (Gemini 3). */
    THINKING_LEVEL,
}

/** Границы бюджета мышления в токенах, которые принимает модель. */
data class TokenRange(val min: Int, val max: Int) {
    init {
        require(min >= 0 && max >= min) { "Некорректный диапазон бюджета токенов: $min..$max" }
    }
}

/**
 * Что модель умеет в управлении усилием. Источник истины и для UI, и для
 * транспортов: интерфейс показывает только объявленные уровни, а сборщик
 * запроса не гадает по имени модели — он получает готовый [ResolvedEffort].
 */
sealed interface ReasoningCapability {

    /** Модель не принимает органов управления мышлением — ручки нет. */
    data object None : ReasoningCapability

    /**
     * @param values объявленные моделью уровни
     * @param default уровень, который модель считает штатным (нужен для «Авто» и подписей)
     * @param mandatory мышление нельзя выключить — уровень NONE не предлагается
     * @param overrides вендорские подмены, применяемые до поиска по расстоянию (Kimi K3: medium → high)
     * @param budget границы бюджета токенов для диалектов с бюджетом
     */
    data class Controls(
        val values: Set<ReasoningEffort>,
        val default: ReasoningEffort? = null,
        val dialect: WireDialect = WireDialect.EFFORT,
        val mandatory: Boolean = false,
        val overrides: Map<ReasoningEffort, ReasoningEffort> = emptyMap(),
        val budget: TokenRange? = null,
    ) : ReasoningCapability
}

/** Результат перевода выбора пользователя на словарь конкретной модели. */
data class ResolvedEffort(
    /** Что отправляем (`null` — поле не участвует в запросе). */
    val level: ReasoningEffort?,
    /** Что просил пользователь — нужно, чтобы показать факт подмены. */
    val requested: ReasoningEffort?,
    /** Запрошенного уровня у модели нет, подобран ближайший объявленный. */
    val clamped: Boolean = false,
) {
    val enabled: Boolean get() = level != null && level != ReasoningEffort.NONE
}

/** Есть ли у модели нативная ручка усилия. */
val ReasoningCapability.supportsEffort: Boolean
    get() = this is ReasoningCapability.Controls && values.isNotEmpty()

/** Уровни в порядке шкалы (AUTO — последним), только объявленные моделью. */
val ReasoningCapability.selectableLevels: List<ReasoningEffort>
    get() {
        val controls = this as? ReasoningCapability.Controls ?: return emptyList()
        val ladder = ReasoningEffort.LADDER.filter { it in controls.values }
        return if (ReasoningEffort.AUTO in controls.values) ladder + ReasoningEffort.AUTO else ladder
    }

/**
 * Перевод выбора пользователя на словарь модели.
 *
 * Правила (свод практик Hermes и Cherry Studio):
 *  - «по умолчанию» остаётся «по умолчанию» — поле не выдумываем;
 *  - поддерживаемый уровень передаётся дословно;
 *  - дальше — вендорский `overrides`, затем ближайший по расстоянию по шкале
 *    (при равенстве расстояний — более сильный уровень);
 *  - NONE никогда не становится целью подмены для включённого мышления —
 *    молча выключать reasoning нельзя;
 *  - пустой словарь уровней = ручки нет, ничего не отправляем.
 */
fun ReasoningCapability.resolveEffort(selection: EffortSelection): ResolvedEffort {
    val controls = this as? ReasoningCapability.Controls ?: return ResolvedEffort(null, selection.level)
    val requested = selection.level ?: return ResolvedEffort(null, null)
    if (requested in controls.values) return ResolvedEffort(requested, requested)
    if (controls.values.isEmpty()) return ResolvedEffort(null, requested)

    controls.overrides[requested]?.let { mapped ->
        if (mapped in controls.values) return ResolvedEffort(mapped, requested, clamped = true)
    }

    var candidates = ReasoningEffort.LADDER.filter { it in controls.values }
        .filter { it != ReasoningEffort.NONE || requested == ReasoningEffort.NONE }
    // Просили ВЫКЛ, но модель не умеет выключаться — сравнимся с самым слабым.
    if (candidates.isEmpty()) candidates = ReasoningEffort.LADDER.filter { it in controls.values }
    if (candidates.isEmpty()) return ResolvedEffort(null, requested)

    val target = ReasoningEffort.rank(requested)
    val best = candidates.minWith(
        compareBy<ReasoningEffort> { abs(ReasoningEffort.rank(it) - target) }
            .thenByDescending { ReasoningEffort.rank(it) },
    )
    return ResolvedEffort(best, requested, clamped = true)
}

/** Доля бюджета токенов, приходящаяся на уровень (нелинейно, как у вендоров). */
val EFFORT_BUDGET_RATIO: Map<ReasoningEffort, Double> = mapOf(
    ReasoningEffort.MINIMAL to 0.05,
    ReasoningEffort.LOW to 0.10,
    ReasoningEffort.MEDIUM to 0.50,
    ReasoningEffort.HIGH to 0.80,
    ReasoningEffort.XHIGH to 0.90,
    ReasoningEffort.MAX to 1.0,
)

/**
 * * Сколько токенов отвести на мышление. `null` — число не отправляем:
 * «Авто» и «по умолчанию» честного числа токенов не имеют, как и модель
 * без объявленного диапазона бюджета.
 */
fun ReasoningCapability.budgetTokens(level: ReasoningEffort?, maxOutput: Int? = null): Int? {
    val controls = this as? ReasoningCapability.Controls ?: return null
    val range = controls.budget ?: return null
    val effective = level?.takeIf { it != ReasoningEffort.AUTO } ?: controls.default ?: return null
    if (effective == ReasoningEffort.NONE) return 0
    val ratio = EFFORT_BUDGET_RATIO[effective] ?: return null
    val byRatio = (range.min + (range.max - range.min) * ratio).toInt().coerceAtLeast(range.min)
    return maxOutput?.let { byRatio.coerceAtMost((it - 1).coerceAtLeast(0)) } ?: byRatio
}

/**
 * Отрепетированные формы ручек. Каталог провайдера и эвристика имён берут
 * формы отсюда — так «что умеет семейство моделей» живёт в одном месте, а
 * разбор имени решает только, какой из профилей применить.
 */
object ReasoningPresets {
    private val LADDER_UP_TO_HIGH = setOf(
        ReasoningEffort.NONE,
        ReasoningEffort.LOW,
        ReasoningEffort.MEDIUM,
        ReasoningEffort.HIGH,
    )

    /** OpenAI-совместимый `reasoning_effort`: minimal…xhigh, выключение разрешено. */
    val OPENAI_EFFORT = ReasoningCapability.Controls(
        values = setOf(
            ReasoningEffort.NONE,
            ReasoningEffort.MINIMAL,
            ReasoningEffort.LOW,
            ReasoningEffort.MEDIUM,
            ReasoningEffort.HIGH,
            ReasoningEffort.XHIGH,
        ),
        default = ReasoningEffort.MEDIUM,
        dialect = WireDialect.EFFORT,
    )

    /** Самохостed-серверы с `reasoning_effort` (DeepSeek V4, GLM, Kimi, Qwen). */
    val COMPAT_EFFORT = ReasoningCapability.Controls(
        values = LADDER_UP_TO_HIGH,
        default = ReasoningEffort.MEDIUM,
        dialect = WireDialect.EFFORT,
    )

    /** Условно-совместимая модель с reasoning/thinking в имени: уровни + режим на модели. */
    val REACT_EFFORT = ReasoningCapability.Controls(
        values = setOf(
            ReasoningEffort.LOW,
            ReasoningEffort.MEDIUM,
            ReasoningEffort.HIGH,
            ReasoningEffort.AUTO,
        ),
        default = ReasoningEffort.MEDIUM,
        dialect = WireDialect.EFFORT,
    )

    /** Старые reasoning-модели без вокабуляра: только «думай / не думай». */
    val MODE_ONLY = ReasoningCapability.Controls(
        values = setOf(ReasoningEffort.NONE, ReasoningEffort.AUTO),
        default = ReasoningEffort.AUTO,
        dialect = WireDialect.EFFORT,
    )

    /** Claude 4.6+: `thinking.type = adaptive` + `output_config.effort`. */
    val ANTHROPIC_ADAPTIVE = ReasoningCapability.Controls(
        values = setOf(
            ReasoningEffort.LOW,
            ReasoningEffort.MEDIUM,
            ReasoningEffort.HIGH,
            ReasoningEffort.MAX,
            ReasoningEffort.AUTO,
        ),
        default = ReasoningEffort.HIGH,
        dialect = WireDialect.ADAPTIVE_EFFORT,
    )

    /** Claude 4.5 и старше: `thinking.budget_tokens`. */
    val ANTHROPIC_BUDGET = ReasoningCapability.Controls(
        values = setOf(
            ReasoningEffort.NONE,
            ReasoningEffort.LOW,
            ReasoningEffort.MEDIUM,
            ReasoningEffort.HIGH,
            ReasoningEffort.MAX,
        ),
        default = ReasoningEffort.MEDIUM,
        dialect = WireDialect.BUDGET_TOKENS,
        budget = TokenRange(1024, 32_000),
    )

    /** Gemini 3: `thinkingLevel`, мышление обязательное. */
    val GEMINI_LEVEL = ReasoningCapability.Controls(
        values = setOf(
            ReasoningEffort.LOW,
            ReasoningEffort.MEDIUM,
            ReasoningEffort.HIGH,
            ReasoningEffort.AUTO,
        ),
        default = ReasoningEffort.HIGH,
        dialect = WireDialect.THINKING_LEVEL,
        mandatory = true,
    )

    /** Gemini 2.5: `thinkingBudget`, «динамически» = Авто. */
    val GEMINI_BUDGET = ReasoningCapability.Controls(
        values = setOf(
            ReasoningEffort.LOW,
            ReasoningEffort.MEDIUM,
            ReasoningEffort.HIGH,
            ReasoningEffort.AUTO,
        ),
        default = ReasoningEffort.HIGH,
        dialect = WireDialect.BUDGET_TOKENS,
        budget = TokenRange(0, 24_576),
    )

    /** Kimi K3: среднее фактически равно высокому, x-high — максимуму. */
    val KIMI_EFFORT = COMPAT_EFFORT.copy(
        values = setOf(
            ReasoningEffort.NONE,
            ReasoningEffort.LOW,
            ReasoningEffort.MEDIUM,
            ReasoningEffort.HIGH,
            ReasoningEffort.XHIGH,
            ReasoningEffort.MAX,
        ),
        overrides = mapOf(
            ReasoningEffort.MEDIUM to ReasoningEffort.HIGH,
            ReasoningEffort.XHIGH to ReasoningEffort.MAX,
        ),
    )

    /** Уровни мышления pi (off…max) — нужны, чтобы задавать `thinkingLevelMap` для coding-сессий. */
    val PI_THINKING = ReasoningCapability.Controls(
        values = setOf(
            ReasoningEffort.NONE,
            ReasoningEffort.MINIMAL,
            ReasoningEffort.LOW,
            ReasoningEffort.MEDIUM,
            ReasoningEffort.HIGH,
            ReasoningEffort.XHIGH,
            ReasoningEffort.MAX,
        ),
        default = ReasoningEffort.MEDIUM,
        dialect = WireDialect.EFFORT,
    )
}
