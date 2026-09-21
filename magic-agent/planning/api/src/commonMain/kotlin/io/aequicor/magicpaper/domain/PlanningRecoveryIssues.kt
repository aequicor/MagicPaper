package io.aequicor.magicpaper.domain

/** Only these owner-authored notices are closed by confirmation of the planning journal. */
object PlanningRecoveryIssues {
    val journalUncertainty = PlanningIssue(IssueKind.UNCERTAIN,
        "Результат операции не подтверждён. Проверьте запрос восстановления сессии перед продолжением.", requiresUser = true)
    val stoppedWithUnconfirmedEffects = PlanningIssue(IssueKind.UNCERTAIN,
        "Выполнение остановлено. Результат предыдущих действий неизвестен; проверьте его перед продолжением.", requiresUser = true)
    /** Cleared only by an exact native acknowledgement, never by journal confirmation alone. */
    val nativeUncertainty = PlanningIssue(IssueKind.UNCERTAIN,
        "Исход предыдущего запуска не подтверждён. Проверьте сохранённый результат перед новым запросом.", requiresUser = true)

    fun owns(issue: PlanningIssue?): Boolean = issue == journalUncertainty || issue == stoppedWithUnconfirmedEffects
}
