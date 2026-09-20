package io.aequicor.magicpaper.domain.tools

import kotlinx.serialization.json.*

internal fun toolArgumentPreview(id: String, args: JsonObject): String {
    fun text(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val preview = when (id) {
        "context.get" -> ""
        "plan.propose" -> text("reply")
        "plan.refine", "stage.send" -> text("message")
        "stage.handoff" -> text("text")
        "stage.resolve", "stage.pause" -> text("reason")
        "web.search" -> text("query")
        "image.generate", "video.generate" -> text("caption").ifBlank { text("prompt") }
        "session.manage" -> text("name").ifBlank { text("kind") }
        "schedule.manage" -> "Правил: ${(args["commands"] as? JsonArray)?.size ?: 0}"
        "questionnaire" -> (args["questions"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull }.joinToString("; ")
        "plan.control" -> when (text("action")) { "pause" -> "Пауза"; "stop" -> "Остановка"; "resume" -> "Продолжение"; "retry" -> "Повтор"; "confirm" -> "Подтверждение"; else -> text("action") }
        else -> args.toString().takeUnless { it == "{}" }.orEmpty()
    }
    return preview.take(512)
}
