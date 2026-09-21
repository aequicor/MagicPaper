package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.util.Id

/** Runs in the same durable write as the source transition, never from a lossy StateFlow observer. */
fun Plan.checkpointMessageEvents(previous: Plan?, now: Long, generate: () -> String = Id::uuid): Plan {
    if (parentSessionId.isBlank() || runId.isBlank()) return this
    val events = messageEvents.toMutableList()
    fun emit(key: String, kind: MessageEventKind, text: String, taskId: String? = null, attempt: String? = null, turn: Int? = null, at: Long = now) {
        val source = "$runId:$key"
        if (events.any { it.sourceKey == source }) return
        events += MessageEvent(uniqueSchedulingId(events.map { it.id }.toSet(), generate), source, id, runId, kind, at, text, taskId,
            attemptId = attempt, turnIndex = turn)
    }
    // The runtime report is a durable handoff even if the app exits before the coordinator hook runs.
    selectedMilestones.forEach { task -> task.attempts.lastOrNull()?.takeIf { it.coordinationState.certainlyPending }?.let { attempt ->
        val reply = stageReplyOrNull(attempt.report)
        if (reply != null) emit("handoff:${attempt.id}-turn-${attempt.turnIndex}", when (reply.kind) {
            StageReplyKind.RESULT -> MessageEventKind.RESULT_RETURNED
            StageReplyKind.QUESTION -> MessageEventKind.QUESTION_RETURNED
            StageReplyKind.BLOCKED -> MessageEventKind.BLOCKED_RETURNED
            StageReplyKind.WAIT -> MessageEventKind.WAIT_REQUESTED
        }, reply.text, task.id, attempt.id, attempt.turnIndex,
            attempt.chatTurns.getOrNull(attempt.turnIndex)?.completedAt?.takeIf { it > 0 } ?: now)
    } }
    coordination.filter { it.runId == runId }.forEach { record ->
        emit("handoff:${record.id}", when (record.reply.kind) {
            StageReplyKind.RESULT -> MessageEventKind.RESULT_RETURNED
            StageReplyKind.QUESTION -> MessageEventKind.QUESTION_RETURNED
            StageReplyKind.BLOCKED -> MessageEventKind.BLOCKED_RETURNED
            StageReplyKind.WAIT -> MessageEventKind.WAIT_REQUESTED
        }, record.reply.text, record.stageId, record.attemptId, record.turnIndex, record.createdAt)
    }
    selectedMilestones.forEach { task ->
        val inherited = runHistory.any { snapshot -> snapshot.milestones.any { it.id == task.id && it.status == MilestoneStatus.DONE && it.attempts == task.attempts } }
        if (task.status == MilestoneStatus.DONE && !inherited)
            emit("success:${task.id}", MessageEventKind.TASK_SUCCEEDED, task.report, task.id, at = task.updatedAt.takeIf { it > 0 } ?: now)
        val error = task.attempts.lastOrNull()?.error
        val oldError = previous?.milestones?.firstOrNull { it.id == task.id }?.attempts?.lastOrNull()?.error
        if (error?.requiresUser == true && error != oldError)
            emit("error:${task.id}:${revision + 1}", MessageEventKind.INTERVENTION_REQUIRED, error.message, task.id,
                task.attempts.lastOrNull()?.id)
    }
    if (phase == ExecutionPhase.COMPLETE) emit("complete", MessageEventKind.RUN_COMPLETED, finalAttempt?.report.orEmpty())
    if (issue?.requiresUser == true && issue != previous?.issue)
        emit("error:${revision + 1}", MessageEventKind.INTERVENTION_REQUIRED, issue!!.message)
    return if (events == messageEvents) this else copy(messageEvents = events)
}
