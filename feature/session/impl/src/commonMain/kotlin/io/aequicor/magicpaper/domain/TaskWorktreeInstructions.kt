package io.aequicor.magicpaper.domain

/** Execution policy must survive native history resume and accompany both engine system prompts. */
internal fun CodingSession.taskWorktreeInstructions(): String {
    val task = taskWorktree ?: return ""
    if (interactionMode != CodingInteractionMode.CODE || stageId != null ||
        pendingRun?.worktreeEnabled != true || pendingRun?.runId != task.taskId ||
        task.phase == TaskWorktreePhase.COMPLETE) return ""
    return """
        Для этой задачи включён управляемый приложением режим Worktree.
        Работай в ${task.path}; исходную папку ${task.sourcePath} не изменяй.
        Ветка назначения: ${task.targetBranch}. После результата приложение само фиксирует
        оставшиеся изменения, проверяет объединение и вливает его в эту ветку.
        Автоматическое слияние уже разрешено включённым режимом Worktree: отдельное
        подтверждение пользователя для фиксации результата, слияния или завершения не требуется.
        После полного выполнения задачи и необходимых проверок обязательно вызови task.handoff
        с outcome=RESULT и checks — массивами аргументов команд проверок (без shell).
        Используй точное доступное имя magicpaper_task_handoff или его MCP-вариант.
        Дождись успешного ответа инструмента и заверши ответ. Не запрашивай через опросник
        разрешение на слияние, завершение или паузу; не предлагай эти действия вместо RESULT.
        Не выполняй слияние в исходную папку самостоятельно и не заявляй, что оно уже выполнено.
        Опросник нужен только для отсутствующих требований или неоднозначного конфликта.
        Реальные вопросы требуют ответа; ошибку проверки или незавершённую работу нельзя
        объявлять RESULT. При неустранимой блокировке передай outcome=BLOCKED.
    """.trimIndent()
}
