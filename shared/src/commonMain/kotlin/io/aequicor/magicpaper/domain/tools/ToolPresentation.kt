package io.aequicor.magicpaper.domain.tools

import kotlinx.serialization.json.*

fun toolDisplayName(id: String): String = when (id) {
    "context.get" -> "Состояние плана"
    "web.search" -> "Поиск источников"
    "questionnaire" -> "Вопросы пользователю"
    "plan.propose" -> "Предложение плана"
    "plan.refine" -> "Разработка плана"
    "plan.recalculate" -> "Пересчёт части плана"
    "plan.control" -> "Управление планом"
    "stage.pause" -> "Приостановка этапов"
    "stage.send" -> "Передача задания"
    "stage.resolve" -> "Рассмотрение результата"
    "session.manage" -> "Управление сессией"
    "schedule.manage" -> "Отложенные сообщения"
    "stage.handoff" -> "Передача результата"
    "file.read" -> "Чтение файла"
    "file.search" -> "Поиск в проекте"
    "file.list" -> "Просмотр папки"
    "git.inspect" -> "Просмотр Git"
    "file.edit" -> "Редактирование файла"
    "file.write" -> "Запись файла"
    "shell.exec" -> "Команда"
    else -> id
}

internal fun toolArgumentPreview(id: String, args: JsonObject): String {
    fun text(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val preview = when (id) {
        "context.get" -> ""
        "plan.propose" -> text("reply")
        "plan.refine", "stage.send" -> text("message")
        "stage.handoff" -> text("text")
        "stage.resolve", "stage.pause" -> text("reason")
        "web.search" -> text("query")
        "session.manage" -> text("name").ifBlank { text("kind") }
        "schedule.manage" -> "Правил: ${(args["commands"] as? JsonArray)?.size ?: 0}"
        "questionnaire" -> (args["questions"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull }.joinToString("; ")
        "plan.control" -> when (text("action")) { "pause" -> "Пауза"; "stop" -> "Остановка"; "resume" -> "Продолжение"; "retry" -> "Повтор"; "confirm" -> "Подтверждение"; else -> text("action") }
        else -> args.toString().takeUnless { it == "{}" }.orEmpty()
    }
    return preview.take(512)
}
