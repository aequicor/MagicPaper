package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.ProviderType
import io.aequicor.magicpaper.domain.EffortSelection
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ModelDefaults
import io.aequicor.magicpaper.domain.ProviderCatalog
import io.aequicor.magicpaper.domain.ReasoningCapability
import io.aequicor.magicpaper.domain.ReasoningEffort
import io.aequicor.magicpaper.domain.ReasoningPresets
import io.aequicor.magicpaper.domain.resolveEffort
import io.aequicor.magicpaper.domain.supportsEffort
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Конфиг модели пи-агента (`models.json` в изолированном PI_CODING_AGENT_DIR)
 * и уровень мышления прогона. Сборка — чистая функция общего кода: мост пишет
 * результат атомарно, а правила проверяются тестами без процессов и ФС.
 *
 * Почему это не константы. Мост раньше резал потолок вывода до 8192 и объявлял
 * модель нерассуждающей (`"reasoning":false`). Клиент пи отправляет переключатели
 * мышления (`enable_thinking`, `reasoning_effort`, `chat_template_kwargs`) только
 * когда `model.reasoning === true`, поэтому запрос уходил без них, Qwen-совместимый
 * сервер думал «сколько сам захочет», а 8192 токенов (дефолт самого пи — 16384)
 * сгорали в рассуждении: `stopReason:"length"`, пустое тело сообщения, ни одного
 * tool-вызова — и прогон выглядел как «Агент завершился без ответа».
 *
 * Отсюда правила:
 *  - лимиты берутся из профиля (`safeContextLimit`/`safeMaxTokens`), а не с потолка;
 *  - `reasoning`, `compat.supportsReasoningEffort` и `thinkingLevelMap` описывают
 *    одну и ту же модель согласованно — возможности берёт [ModelDefaults];
 *  - рассуждающей модели поднимаем потолок вывода до [REASONING_MIN_MAX_TOKENS]:
 *    урок уже выучен в чат-пути, где `max_tokens` поднимают над бюджетом мышления
 *    (`LlmPayloads.anthropic`);
 *  - `reasoning_effort` и `thinking_budget` вместе не отправляем никогда: DashScope
 *    их отвергает парой, поэтому бюджет токенов не включаем — только уровни.
 */
object PiModelsConfig {

    /** Идентификатор провайдера в конфиге пи (уходит ещё и в `--provider`). */
    const val PROVIDER_ID = "magicpaper"

    /** Транспорт пи для любого OpenAI-совместимого сервера. */
    const val API = "openai-completions"

    /** Заглушка ключа для серверов без авторизации (локальный Ollama и т.п.). */
    const val ANONYMOUS_KEY = "magicpaper"

    /**
     * Минимальный потолок вывода рассуждающей модели — столько же, сколько
     * считает пи по умолчанию (docs/models.md, «Model Configuration»).
     * Рассуждение платит из того же `maxTokens`, поэтому профиль с 8192
     * оставлял телу сообщения ноль токенов.
     */
    const val REASONING_MIN_MAX_TOKENS = 16_384

    /**
     * Уровень мышления кодинг-прогона, когда в профиле выбрано «по умолчанию
     * провайдера». Детерминизм важнее вежливости к модели: без явного уровня pi
     * подставит свой дефолт (medium), а при `reasoning:false` не отправит
     * переключатель вовсе — и сервер будет думать ровно столько, сколько захочет,
     * съедая потолок вывода. `medium` — компромисс: правка кода требует разбора,
     * но «высокое» усилие на больших файлах упорно уходит в обрезку.
     * Выбор пользователя (включая «выключено») всегда сильнее этой константы.
     */
    val DEFAULT_CODING_EFFORT: EffortSelection = EffortSelection.of(ReasoningEffort.MEDIUM)

    /** Вокабуляр мышления pi: имя уровня у pi ↔ наш канонический уровень. */
    private val PI_LEVELS: List<Pair<String, ReasoningEffort>> = listOf(
        "off" to ReasoningEffort.NONE,
        "minimal" to ReasoningEffort.MINIMAL,
        "low" to ReasoningEffort.LOW,
        "medium" to ReasoningEffort.MEDIUM,
        "high" to ReasoningEffort.HIGH,
        "xhigh" to ReasoningEffort.XHIGH,
        "max" to ReasoningEffort.MAX,
    )

    /** Что мост сообщает пи о рассуждении конкретной модели профиля. */
    data class Reasoning(
        /** Поле `reasoning`: с `false` пи не шлёт никаких thinking-переключателей. */
        val enabled: Boolean,
        /** Потолок вывода, записанный в `maxTokens` (с подстраховкой для рассуждения). */
        val maxTokens: Int,
        /** Значение `--thinking`; null — переключатель не отправляем: уровень нечем выразить. */
        val thinkingLevel: String?,
        /** `compat.thinkingFormat` по семейству модели; null — схема по умолчанию. */
        val thinkingFormat: String?,
        /** `thinkingLevelMap`: строка — значение провайдеру, null — уровень недоступен. */
        val thinkingLevelMap: Map<String, String?>,
    )

    /** Итоговый `models.json` провайдера — тот же JSON, что пишет мост. */
    fun json(profile: LlmProfile, providerId: String = PROVIDER_ID): String =
        root(profile, providerId).toString()

    /** То же деревом: тестам удобнее читать поля, чем подстроки. */
    fun root(profile: LlmProfile, providerId: String = PROVIDER_ID) = buildJsonObject {
        put("providers", buildJsonObject {
            put(providerId, buildJsonObject {
                put("baseUrl", profile.baseUrl.trimEnd('/'))
                put("api", when (profile.provider) {
                    ProviderType.ANTHROPIC -> "anthropic-messages"
                    ProviderType.GOOGLE -> "google-generative-ai"
                    else -> API
                })
                put("apiKey", profile.apiKey.ifBlank { ANONYMOUS_KEY })
                val reasoning = reasoning(profile)
                // supportsReasoningEffort обязано совпадать с reasoning: при
                // несогласованности пи объявляет мышление, но не передаёт уровень.
                put("compat", buildJsonObject {
                    put("supportsDeveloperRole", false)
                    put("supportsReasoningEffort", reasoning.enabled)
                    reasoning.thinkingFormat?.let { put("thinkingFormat", it) }
                })
                put("models", buildJsonArray {
                    add(buildJsonObject {
                        put("id", profile.modelId)
                        put("name", profile.modelId)
                        put("reasoning", reasoning.enabled)
                        put("contextWindow", contextWindow(profile))
                        put("maxTokens", reasoning.maxTokens)
                        if (reasoning.thinkingLevelMap.isNotEmpty()) {
                            put("thinkingLevelMap", buildJsonObject {
                                reasoning.thinkingLevelMap.forEach { (level, value) ->
                                    if (value == null) put(level, JsonNull) else put(level, value)
                                }
                            })
                        }
                    })
                })
            })
        })
    }

    /** Верхняя граница контекста — из профиля (pi по умолчанию молчит про 128000). */
    fun contextWindow(profile: LlmProfile): Int = profile.advanced.safeContextLimit

    /**
     * Потолок вывода для `maxTokens` конфига: значение профиля, но рассуждающей
     * модели — не меньше [REASONING_MIN_MAX_TOKENS].
     */
    fun maxTokens(profile: LlmProfile, modelId: String = profile.modelId): Int {
        val base = profile.advanced.safeMaxTokens
        val controls = controls(profile, modelId) ?: return base
        return if (profile.modelLibraryVersion >= 1) base else if (controls.supportsEffort) maxOf(base, REASONING_MIN_MAX_TOKENS) else base
    }

    /** Полная картина о рассуждении модели — её же читает [root]. */
    fun reasoning(profile: LlmProfile, modelId: String = profile.modelId): Reasoning {
        val controls = controls(profile, modelId)?.takeIf { it.supportsEffort }
        val levelMap = controls?.let { thinkingLevelMap(it) } ?: emptyMap()
        return Reasoning(
            enabled = controls != null,
            maxTokens = maxTokens(profile, modelId),
            thinkingLevel = controls?.let { thinkingLevel(profile, modelId, it, levelMap) },
            thinkingFormat = if (controls != null) thinkingFormat(profile, modelId) else null,
            thinkingLevelMap = levelMap,
        )
    }

    /**
     * Уровень для `--thinking`: выбор пользователя из профиля, приведённый к
     * вокабуляру pi и к уровням, которые модель принимает ([thinkingLevelMap]).
     * `null` — переключатель не отправляем: у модели либо нет ручек, либо она
     * сама выбирает режим ([ReasoningPresets.MODE_ONLY]).
     */
    fun thinkingLevel(profile: LlmProfile, modelId: String = profile.modelId): String? =
        controls(profile, modelId)?.takeIf { it.supportsEffort }?.let {
            thinkingLevel(profile, modelId, it, thinkingLevelMap(it))
        }

    private fun thinkingLevel(
        profile: LlmProfile,
        modelId: String,
        controls: ReasoningCapability.Controls,
        levelMap: Map<String, String?>,
    ): String? {
        val selection = profile.effortSelectionFor(modelId)
        val effective = if (selection.isDefault) DEFAULT_CODING_EFFORT else selection
        val resolved = controls.resolveEffort(effective).level
        // «Режим на модели» (AUTO) — переключатель не отправляем: решает сервер.
        if (resolved == null || resolved == ReasoningEffort.AUTO) return null
        val level = piLevelOf(resolved) ?: return null
        // Модель уровень не принимает — ближайший разрешённый, но не «off»:
        // выключать рассуждение без спроса нельзя (то же правило у resolveEffort).
        if (levelMap[level] != null) return level
        return PI_LEVELS.firstOrNull { (name, _) -> name != "off" && levelMap[name] != null }?.first
    }

    /** Канонический уровень → имя уровня pi. */
    private fun piLevelOf(effort: ReasoningEffort): String? =
        PI_LEVELS.firstOrNull { it.second == effort }?.first

    /**
     * `thinkingLevelMap` по вокабуляру [ReasoningPresets.PI_THINKING]
     * (docs/models.md, «Thinking Level Map»): уровень, который модель объявляет,
     * уходит провайдеру дословно, остальные помечены null — pi их прячет и не
     * выставляет. «off» недоступен, когда мышление обязательное (`mandatory`) или
     * модель не знает выключенных уровней: молча выключать рассуждение нельзя.
     */
    private fun thinkingLevelMap(controls: ReasoningCapability.Controls): Map<String, String?> =
        PI_LEVELS.associate { (level, effort) ->
            val usable = if (effort == ReasoningEffort.NONE) {
                !controls.mandatory && effort in controls.values
            } else {
                effort in controls.values
            }
            level to if (usable) effort.wire else null
        }

    /**
     * Диалект переключателей мышления по семейству модели: наружные Qwen-эндпоинты
     * понимают верхнеуровневый `enable_thinking` (`"qwen"`), локальные серверы —
     * `chat_template_kwargs` (`"qwen-chat-template"`). Остальным — схема по
     * умолчанию (`reasoning_effort`), которую выбирает сам pi.
     */
    private fun thinkingFormat(profile: LlmProfile, modelId: String): String? = when {
        ProviderCatalog.familyOf(modelId) != "qwen" -> null
        isLocalEndpoint(profile.baseUrl) -> "qwen-chat-template"
        else -> "qwen"
    }

    /** Локальный сервер (Ollama, LM Studio, llama.cpp) — по имени хоста. */
    private fun isLocalEndpoint(baseUrl: String): Boolean {
        val host = baseUrl.substringAfter("://", baseUrl)
            .substringBefore('/')
            .substringBeforeLast(':')
            .trim('[', ']')
            .lowercase()
        return host == "localhost" || host == "::1" || host == "0.0.0.0" ||
            host == "host.docker.internal" ||
            // Домашние и офисные сети: там и живут локальные серверы с chat template.
            host.startsWith("127.") || host.startsWith("192.168.") || host.startsWith("10.")
    }

    private fun controls(profile: LlmProfile, modelId: String): ReasoningCapability.Controls? =
        ModelDefaults.capability(profile, modelId) as? ReasoningCapability.Controls

    /**
     * Пояснение к обрезке для итоговой ошибки: потолок из того, что реально
     * записано в конфиг, числа из `usage` последнего `message_end` и совет,
     * который пользователю по силам. Если профиль и так уже на дефолте pi,
     * совет «поднять лимит» был бы обманом — тогда совет про усилие и размер
     * задачи, а не про цифру, которую поднимать некуда.
     */
    fun truncationAdvice(
        profile: LlmProfile,
        outputTokens: Int? = null,
        reasoningTokens: Int? = null,
        modelId: String = profile.modelId,
    ): String {
        val ceiling = maxTokens(profile, modelId)
        val configured = profile.advanced.safeMaxTokens
        val spent = outputTokens ?: ceiling
        val thinking = reasoningTokens ?: 0
        val effort = thinkingLevel(profile, modelId)
        return buildString {
            append("Модель израсходовала весь лимит вывода")
            if (thinking > 0 && thinking >= spent) append(" на рассуждение")
            append(" (").append(spent).append(" из ").append(ceiling).append(" токенов")
            if (thinking > 0) append(", из них ").append(thinking).append(" — рассуждение")
            if (configured < ceiling) append(", в профиле стоит ").append(configured)
            append("). ")
            if (configured < ceiling) {
                append("Поднимите «максимум токенов» в профиле до ≥")
                    .append(REASONING_MIN_MAX_TOKENS).append(". ")
            }
            append("Усилие сейчас — ").append(effort ?: "не задаётся")
            append(": снизьте его или разбейте задачу на части.")
        }
    }
}
