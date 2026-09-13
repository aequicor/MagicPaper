package io.aequicor.magicpaper.data.llm

import kotlinx.serialization.json.*

internal fun codexWebTitle(item: JsonObject): String {
    val action = item["action"] as? JsonObject
    fun text(key: String) = (action?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    val label = when (text("type")) {
        "search" -> "Поиск источников"
        "openPage" -> "Открытие страницы"
        "findInPage" -> "Поиск на странице"
        else -> "Веб-операция"
    }
    val detail = when (text("type")) {
        "search" -> (action?.get("queries") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it.isNotBlank() }
            .joinToString("; ").ifBlank { text("query") }
        "openPage" -> text("url")
        "findInPage" -> listOf(text("pattern"), text("url")).filter { it.isNotBlank() }.joinToString(" · ")
        else -> ""
    }.ifBlank { (item["query"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
    return label + if (detail.isBlank()) "" else " · ${detail.take(1500)}"
}
