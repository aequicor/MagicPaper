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
