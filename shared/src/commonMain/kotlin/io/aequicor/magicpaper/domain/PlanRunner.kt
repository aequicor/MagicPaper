package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.util.Id

/**
 * Исполнитель плана: проходит мэилстоуны по порядку, каждый выполняет
 * закреплённым агентом (профилем) через кодинг-рантайм в отдельной
 * кодинг-сессии плана (свой контекст пи между шагами, свой журнал)
 * и проверяет достижимость вердиктом [MilestoneVerifier]. Остановка —
 * на первом недостижимом мэилстоуне (план помечается проваленным),
 * чтобы не строить на песке. Зависит от портов, не от реализаций (DIP).
 */
class PlanRunner(
    private val runtime: CodingRuntime,
    private val verifier: MilestoneVerifier,
    private val projectsRepo: CodingProjectRepository? = null,
) {

    /** Рантайм однопоточный: второй запуск плана не стартует, пока идёт первый. */
    private val running = kotlinx.coroutines.sync.Mutex()

    /**
     * Выполняет план. [onUpdate] получает актуальный план после каждого
     * изменения статуса — для живой ленты в UI. Возвращает итоговый план.
     * [session] — кодинг-сессия плана: пи-контекст продолжается между шагами,
     * журнал исполнения пишется в неё же. [isAborted] — флаг прерывания
     * пользователем: проверяется между вехами.
     */
    suspend fun run(
        plan: Plan,
        project: CodingProject,
        session: CodingSession,
        profiles: List<LlmProfile>,
        judge: LlmProfile?,
        onUpdate: suspend (Plan) -> Unit,
        isAborted: () -> Boolean = { false },
    ): Plan {
        if (!running.tryLock()) return plan
        try {
            return runLocked(plan, project, session, profiles, judge, onUpdate, isAborted)
        } finally {
            running.unlock()
        }
    }

    private suspend fun runLocked(
        plan: Plan,
        project: CodingProject,
        session: CodingSession,
        profiles: List<LlmProfile>,
        judge: LlmProfile?,
        onUpdate: suspend (Plan) -> Unit,
        isAborted: () -> Boolean,
    ): Plan {
        var currentSession = session
        var current = plan.copy(status = PlanStatus.RUNNING, updatedAt = plan.updatedAt)
        onUpdate(current)
        while (true) {
            if (isAborted()) {
                current = current.copy(status = PlanStatus.STOPPED)
                onUpdate(current)
                return current
            }
            val milestone = current.nextPending ?: break
            val outcome = executeMilestone(current, milestone, project, currentSession, profiles, judge, onUpdate)
            current = outcome.plan
            currentSession = outcome.session
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

    /** Итог шага: обновлённый план и сессия (мог смениться пи-идентификатор). */
    private data class StepOutcome(val plan: Plan, val session: CodingSession)

    private suspend fun executeMilestone(
        plan: Plan,
        milestone: Milestone,
        project: CodingProject,
        session: CodingSession,
        profiles: List<LlmProfile>,
        judge: LlmProfile?,
        onUpdate: suspend (Plan) -> Unit,
    ): StepOutcome {
        val profile = profiles.firstOrNull { it.id == milestone.agentProfileId && it.configured }
            ?: profiles.firstOrNull { it.configured }

        // Шаг 1: пометка «выполняется» — лента сразу показывает активную веху.
        val runningPlan = plan.replaceMilestone(milestone.copy(status = MilestoneStatus.ACTIVE))
        onUpdate(runningPlan)

        if (profile == null) {
            val failed = runningPlan.replaceMilestone(
                milestone.copy(status = MilestoneStatus.FAILED, checkNote = "Нет настроенного источника для выполнения.")
            )
            return StepOutcome(failed, session)
        }

        // Шаг 2: прогон агента в папке проекта, в сессии плана.
        val prompt = executionPrompt(plan, milestone)
        var collectedSessionId = ""
        val report = collectReport(runtime.run(project, session, prompt, profile)) { sessionId ->
            collectedSessionId = sessionId
        }
        // Пи-агент выдаёт свой идентификатор сессии — продолжаем контекст между шагами.
        var currentSession = session
        if (collectedSessionId.isNotBlank() && collectedSessionId != session.piSessionId) {
            currentSession = session.copy(piSessionId = collectedSessionId)
            projectsRepo?.runCatching { saveSession(currentSession) }
        }
        val executed = runningPlan.replaceMilestone(
            milestone.copy(status = MilestoneStatus.ACTIVE, report = report)
        )
        onUpdate(executed)

        // Шаг 3: проверка достижимости цели мэилстоуна.
        val verdict = verifier.verify(milestone.copy(report = report), plan.goal, report, judge)
        val finalStatus = if (verdict.passed) MilestoneStatus.DONE else MilestoneStatus.FAILED
        val verified = executed.replaceMilestone(
            milestone.copy(
                status = finalStatus,
                report = report,
                checkNote = verdict.note,
            )
        )
        // Итог вехи сразу в ленту: и для живого графика, и для прерывания.
        onUpdate(verified)

        // Шаг 4: журнал исполнения — промпт и отчёт попадают в сессию плана,
        // чтобы ход работы был виден рядом с обычным диалогом.
        appendLog(project, currentSession, prompt, report, verdict, finalStatus)

        return StepOutcome(verified, currentSession)
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

    /** Записывает ход шага в журнал сессии плана (если хранилище доступно). */
    private suspend fun appendLog(
        project: CodingProject,
        session: CodingSession,
        prompt: String,
        report: String,
        verdict: Verdict,
        status: MilestoneStatus,
    ) {
        val repo = projectsRepo ?: return
        runCatching {
            val log = repo.messages(project.id, session.id)
            val glyph = if (status == MilestoneStatus.DONE) "✔" else "✕"
            val entry = CodingMessage(
                id = Id.new(),
                role = CodingRole.AGENT,
                text = report,
                activity = listOf("$glyph ${verdict.note}"),
                failed = status != MilestoneStatus.DONE,
                createdAt = Id.now(),
            )
            repo.saveMessages(project.id, session.id, log + entry)
        }
    }

    /** Склеивает поток событий в итоговый текст отчёта; сбой — пометкой. */
    private suspend fun collectReport(
        events: kotlinx.coroutines.flow.Flow<CodingEvent>,
        onSessionId: (String) -> Unit = {},
    ): String {
        val text = StringBuilder()
        var failure: String? = null
        events.collect { event ->
            when (event) {
                is CodingEvent.SessionStarted -> if (event.sessionId.isNotBlank()) onSessionId(event.sessionId)
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
