package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.CodingModel
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

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
