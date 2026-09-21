package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.CodingEngine
import io.aequicor.magicpaper.domain.CodingModel
import io.aequicor.magicpaper.domain.CodingSession
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ModelDefaults
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Идентификатор провайдера в словаре Codex: с ним же запускается нативная подписка. */
internal const val CODEX_NATIVE_PROVIDER = "openai"

/**
 * Каталог моделей ровно так, как его объявляет `model/list`. Уровни остаются строками Codex
 * (`max` и `ultra` — разные уровни, шкала приложения их бы склеила), скрытые модели пропускаются,
 * а отсутствие `inputModalities` читается как «текст и картинки» — так Codex сам трактует запись
 * без этого поля. [contextWindows] — действующие окна из собственного кэша Codex: `model/list`
 * их не отдаёт.
 */
internal fun codexCodingModels(items: List<JsonObject>, contextWindows: Map<String, Int>): List<CodingModel> =
    items.mapNotNull { item ->
        if ((item["hidden"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
        val id = item.text("model") ?: item.text("id") ?: return@mapNotNull null
        val levels = (item["supportedReasoningEfforts"] as? JsonArray).orEmpty()
            .mapNotNull { it.jsonObject.text("reasoningEffort") }
            .distinct()
        val modalities = (item["inputModalities"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        CodingModel(
            provider = CODEX_NATIVE_PROVIDER,
            id = id,
            name = item.text("displayName") ?: id,
            contextWindow = contextWindows[id],
            levels = levels,
            defaultLevel = item.text("defaultReasoningEffort"),
            acceptsImages = modalities?.contains("image") ?: true,
        )
    }.distinctBy { it.id }

/** Что уйдёт в `thread/start` и `turn/start`: модель и уровень; [effort] `null` — поле не отправляется. */
internal data class CodexLaunchModel(val modelId: String, val effort: String?)

/**
 * Нативный выбор сессии ([CodingSession.codingModel]) уходит Codex как есть: без подмены
 * профилем и без клампа уровня к шкале приложения. Уровень `null` — «по умолчанию»: поле
 * `effort` не отправляется, и умолчание определяет сам Codex. Допустимость уровня для модели
 * здесь не проверяется: сверку с каталогом делает тот, кто выбирает (интерфейс, допуск этапа),
 * а на запуске Codex отвечает на неподходящее значение собственной ошибкой.
 *
 * Без нативного выбора действует прежний путь через профиль. Каталог Codex перечисляет модели
 * подписки, поэтому нативный выбор требует прямого подключения ([direct]): идентификатор из
 * каталога не имеет смысла на чужом сервере за прокси Responses.
 */
internal fun codexLaunchModel(session: CodingSession, profile: LlmProfile, direct: Boolean): CodexLaunchModel {
    val selection = session.codingModel
        ?: return CodexLaunchModel(profile.modelId, profile.resolveEffort(ModelDefaults.capability(profile)).level?.wire)
    require(selection.engine == CodingEngine.CODEX && selection.provider == CODEX_NATIVE_PROVIDER) {
        "Native model selection belongs to another engine or provider"
    }
    require(direct) { "Native Codex model selection requires the direct ChatGPT connection" }
    return CodexLaunchModel(selection.modelId, selection.level)
}

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
