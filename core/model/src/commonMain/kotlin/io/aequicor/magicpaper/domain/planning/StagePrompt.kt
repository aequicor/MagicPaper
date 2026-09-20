package io.aequicor.magicpaper.domain.planning

import io.aequicor.magicpaper.domain.*

/** Builds the worker request from one checkpoint; environment guidance is supplied by its owner. */
fun stageWorkerPrompt(plan: Plan, stage: Milestone, attempt: StageAttempt, guidance: String, extraInstructions: String): String {
    val context = DecisionCompiler.compile(plan).dependencies[stage.id].orEmpty().joinToString("\n") { dep ->
        plan.milestones.first { it.id == dep }.let { "${it.title}: ${it.report}\nПриёмка этапа: ${it.checkNote}" }
    }
    return """
                    Общая цель: ${plan.goal}
                    Этап: ${stage.title}
                    Задача: ${stage.description}
                    Критерии проверки: ${stage.acceptance.ifBlank { stage.description }}
                    Подтверждения по каждому критерию:
                    ${stage.criteria().joinToString("\n") { "${it.id}: ${it.description} (${if (it.required) "обязательно" else "необязательно"}; ${it.environment.label()})" }}
                    Результаты предшественников: $context
                    Предыдущая работа и диагностика: ${attempt.report}\n${attempt.error?.message.orEmpty()}
                    Состояние перед продолжением: ${attempt.activity}
                    Продолжай с фактического состояния файлов; сначала проверь уже выполненные изменения.
                    После прерывания проверь последствия незавершённой команды; не повторяй её автоматически.
                    Работай только в этой рабочей папке. Не выполняй внешних публикаций.
                    Выполни проверки критериев и в конце укажи команды, результаты и изменённые файлы.
                    ${guidance}
                """.trimIndent() + "\n" + extraInstructions
}

/** Show the workspace limitation once across resumed worker turns. */
fun stageWorkspaceNotice(workspace: PlanWorkspace, attempt: StageAttempt): String? =
    if (!workspace.git && attempt.steps.none { it.isVisibleActivity && it.title.contains("Git недоступен") })
        "Git недоступен: изменения выполняются без отдельных веток и коммитов. Репозиторий не создан."
    else null

fun stageMergePrompt(stage: Milestone, attempt: StageAttempt): String =
    "Разреши текущий Git merge-конфликт, сохрани результаты обеих ветвей, добавь разрешённые файлы в индекс. Если объединение уже сделано, продолжи проверки. Критерии: ${stage.acceptance.ifBlank { stage.description }}. Предыдущий отчёт: ${attempt.mergeReport}. Отчитайся о фактических проверках."
