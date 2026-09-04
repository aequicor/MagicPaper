package io.aequicor.magicpaper.domain

/**
 * Исполнитель плана: проходит мэилстоуны по порядку, каждый выполняет
 * закреплённым агентом (профилем) через кодинг-рантайм и проверяет
 * достижимость вердиктом [MilestoneVerifier]. Остановка — на первом
 * недостижимом мэилстоуне (план помечается проваленным), чтобы не
 * строить на песке. Зависит от порта, а не от конкретной проверки (DIP).
 */
class PlanRunner(
    private val runtime: CodingRuntime,
    private val verifier: MilestoneVerifier,
) {

    /** Рантайм однопоточный: второй запуск плана не стартует, пока идёт первый. */
    private val running = kotlinx.coroutines.sync.Mutex()

    /**
     * Выполняет план. [onUpdate] получает актуальный план после каждого
     * изменения статуса — для живой ленты в UI. Возвращает итоговый план.
     * Одновременно может идти один прогон плана (рантайм однопоточный).
     * [isAborted] — флаг прерывания пользователем: проверяется между вехами.
     */
    suspend fun run(
        plan: Plan,
        project: CodingProject,
        profiles: List<LlmProfile>,
        judge: LlmProfile?,
        onUpdate: suspend (Plan) -> Unit,
        isAborted: () -> Boolean = { false },
    ): Plan {
        if (!running.tryLock()) return plan
        try {
            return runLocked(plan, project, profiles, judge, onUpdate, isAborted)
        } finally {
            running.unlock()
        }
    }

    private suspend fun runLocked(
        plan: Plan,
        project: CodingProject,
        profiles: List<LlmProfile>,
        judge: LlmProfile?,
        onUpdate: suspend (Plan) -> Unit,
        isAborted: () -> Boolean,
    ): Plan {
        var current = plan.copy(status = PlanStatus.RUNNING, updatedAt = plan.updatedAt)
        onUpdate(current)
        while (true) {
            if (isAborted()) {
                current = current.copy(status = PlanStatus.STOPPED)
                onUpdate(current)
                return current
            }
            val milestone = current.nextPending ?: break
            current = executeMilestone(current, milestone, project, profiles, judge, onUpdate)
            val updated = current.milestones.first { it.id == milestone.id }
            if (updated.status == MilestoneStatus.FAILED) {
                current = current.copy(status = PlanStatus.FAILED)
                onUpdate(current)
                return current
            }
        }
        current = current.copy(status = PlanStatus.DONE)
        onUpdate(current)
        return current
    }

    private suspend fun executeMilestone(
        plan: Plan,
        milestone: Milestone,
        project: CodingProject,
        profiles: List<LlmProfile>,
        judge: LlmProfile?,
        onUpdate: suspend (Plan) -> Unit,
    ): Plan {
        val profile = profiles.firstOrNull { it.id == milestone.agentProfileId && it.configured }
            ?: profiles.firstOrNull { it.configured }

        // Шаг 1: пометка «выполняется» — лента сразу показывает активную веху.
        val runningPlan = plan.replaceMilestone(milestone.copy(status = MilestoneStatus.ACTIVE))
        onUpdate(runningPlan)

        if (profile == null) {
            return runningPlan.replaceMilestone(
                milestone.copy(status = MilestoneStatus.FAILED, checkNote = "Нет настроенного источника для выполнения.")
            )
        }

        // Шаг 2: прогон агента в папке проекта.
        val report = collectReport(runtime.run(project, executionPrompt(plan, milestone), profile))
        val executed = runningPlan.replaceMilestone(
            milestone.copy(status = MilestoneStatus.ACTIVE, report = report)
        )
        onUpdate(executed)

        // Шаг 3: проверка достижимости цели мэилстоуна.
        val verdict = verifier.verify(milestone.copy(report = report), plan.goal, report, judge)
        val verified = executed.replaceMilestone(
            milestone.copy(
                status = if (verdict.passed) MilestoneStatus.DONE else MilestoneStatus.FAILED,
                report = report,
                checkNote = verdict.note,
            )
        )
        // Итог вехи сразу в ленту: и для живого графика, и для прерывания.
        onUpdate(verified)
        return verified
    }

    /** Промпт для агента: цель + шаг + критерий, чтобы агент знал, что проверят. */
    private fun executionPrompt(plan: Plan, milestone: Milestone): String = buildString {
        appendLine("Выполняется по плану задачи.")
        appendLine("Общая цель: ${plan.goal}")
        appendLine("Текущий шаг: ${milestone.title}")
        if (milestone.description.isNotBlank()) appendLine("Что сделать и как проверить: ${milestone.description}")
        appendLine("Выполни шаг полностью, внутри этой папки проекта.")
        append("В конце кратко отчитайся: что сделано и как это подтверждает критерий шага.")
    }

    /** Склеивает поток событий в итоговый текст отчёта; сбой — пометкой. */
    private suspend fun collectReport(events: kotlinx.coroutines.flow.Flow<CodingEvent>): String {
        val text = StringBuilder()
        var failure: String? = null
        events.collect { event ->
            when (event) {
                is CodingEvent.TextDelta -> text.append(event.delta)
                is CodingEvent.FinalText -> if (event.text.isNotBlank()) {
                    text.setLength(0)
                    text.append(event.text)
                }
                is CodingEvent.Failed -> failure = event.message
                else -> Unit
            }
        }
        val answer = text.toString().trim()
        return when {
            failure != null && answer.isBlank() -> "✕ Прогон не удался: $failure"
            failure != null -> "$answer\n\n(в процессе был сбой: $failure)"
            answer.isBlank() -> "Агент не оставил отчёта."
            else -> answer
        }
    }

    private fun Plan.replaceMilestone(milestone: Milestone): Plan =
        copy(milestones = milestones.map { if (it.id == milestone.id) milestone else it })
}
