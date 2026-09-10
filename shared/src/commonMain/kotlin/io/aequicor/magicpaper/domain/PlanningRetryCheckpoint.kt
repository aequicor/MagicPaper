package io.aequicor.magicpaper.domain

/** A questionnaire authorizes its stopped execution, even when its custom instructions change the inbox. */
internal fun Plan.requireRetryCheckpoint(expected: Plan) {
    fun StageAttempt.binding() = listOf(id, sessionId, turnIndex, sessionGeneration, engine, engineSessionId, path, assignment)
    val stages = milestones.associateBy { it.id }
    val unchangedAttempts = expected.milestones.all { original ->
        stages[original.id]?.attempts?.map { it.binding() } == original.attempts.map { it.binding() }
    } && milestones.filter { current -> expected.milestones.none { it.id == current.id } }.all { it.attempts.isEmpty() }
    val sameFinal = finalAttempt?.binding() == expected.finalAttempt?.binding() ||
        finalAttempt == null && expected.finalAttempt != null && expected.finalAttempt in finalAttemptHistory
    require(id == expected.id && projectId == expected.projectId && parentSessionId == expected.parentSessionId &&
        runId == expected.runId && intent == expected.intent && stopping == expected.stopping &&
        confirmedRevision == expected.confirmedRevision && unchangedAttempts && sameFinal &&
        journal.filter { it.operation.startsWith("stop-") } == expected.journal.filter { it.operation.startsWith("stop-") }) {
        "Состояние запуска изменилось; ответьте на актуальный запрос восстановления"
    }
}
