package io.aequicor.magicpaper.domain

/** Peer observations are context, never an instruction to reopen work. */
internal fun Plan.acceptsPeerContext(sessions: List<CodingSession>): Boolean =
    confirmedRevision != null && runId.isNotBlank() && intent == ExecutionIntent.RUN && !stopping &&
        phase != ExecutionPhase.COMPLETE && sessions.any { it.id == parentSessionId && !it.archived }

internal fun Plan.sharesPeerWorkspace(other: Plan): Boolean {
    if (id == other.id || projectId != other.projectId || !sharedWorkspace || !other.sharedWorkspace) return false
    val ownPath = workspace?.integrationPath?.trimEnd('/', '\\')?.takeIf { it.isNotBlank() }
    val otherPath = other.workspace?.integrationPath?.trimEnd('/', '\\')?.takeIf { it.isNotBlank() }
    return ownPath == null || otherPath == null || ownPath == otherPath
}

internal fun Plan.currentPeerResults(): List<CoordinationRecord> = selectedMilestones.mapNotNull { stage ->
    val attempt = stage.attempts.lastOrNull() ?: return@mapNotNull null
    stageRecords(stage.id, attempt).lastOrNull { it.reply.kind == StageReplyKind.RESULT }
}

internal fun peerContextMessageId(source: Plan, target: Plan, stageId: String): String =
    "peer-context:" + listOf(source.id, source.runId, stageId, target.id, target.runId).joinToString(":") { "${it.length}:$it" }

/** Recognize the old generator by its source, exact record identity and full payload, not a text prefix. */
private fun Plan.legacyPeerCommandBase(delivery: PlanDelivery, peers: List<Plan>): Milestone? {
    if (delivery.state !in setOf(DeliveryState.QUEUED, DeliveryState.CANCELLED) || delivery.replyTo != null || delivery.sourceRunId != null) return null
    val sources = peers.filter { it.projectId == projectId && it.id != id && it.parentSessionId.isNotBlank() &&
        it.parentSessionId == delivery.sourceSessionId }
    for (base in milestones) {
        if (delivery.targetStageId != base.id && delivery.targetStageId != "${base.id}-followup-${delivery.id}") continue
        for (source in sources) for (other in source.coordination.filter { it.reply.kind == StageReplyKind.RESULT }) {
            if (delivery.id == "overlap-${base.id}-${other.id}") {
                val matches = coordination.filter { it.stageId == base.id && it.reply.kind == StageReplyKind.RESULT }.any { own ->
                    val files = own.reply.changedFiles.filter { it in other.reply.changedFiles }
                    files.isNotEmpty() && delivery.text == "Обнаружено пересечение файлов с планом ${source.goal}: $files. Перечитай фактическое состояние, согласуй изменения без отката чужих файлов и повтори проверки. Не повторяй уже выполненные правки."
                }
                if (matches) return base
            }
            if (delivery.id == "${source.id}-${other.stageId}-${other.reply.hashCode()}-peer-${base.id}") {
                val sourceStage = source.milestones.firstOrNull { it.id == other.stageId } ?: continue
                val info = "План «${source.goal}», этап «${sourceStage.title}»: ${other.reply.text}\nИзменённые файлы: ${other.reply.changedFiles.joinToString()}"
                if (delivery.text == "Сведения соседнего плана. Перед продолжением перечитай затронутые файлы, не перезаписывай чужие изменения. $info") return base
            }
        }
    }
    return null
}

/** Preserve executed receipts and explicit assignments. Remove only untouched leaves made by the old generator. */
internal fun Plan.cancelLegacyPeerCommands(peers: List<Plan>, admittedAttempts: Map<String, SessionLegacyAttempt> = emptyMap()): Plan {
    val commands = deliveries.mapNotNull { delivery -> legacyPeerCommandBase(delivery, peers)?.let { delivery to it } }
    if (commands.isEmpty()) return this
    val cancelledIds = commands.map { it.first.id }.toSet()
    val removable = commands.mapNotNull { (delivery, base) ->
        val generatedId = "${base.id}-followup-${delivery.id}"
        val generated = milestones.firstOrNull { it.id == generatedId } ?: return@mapNotNull null
        val expected = base.copy(id = generatedId, title = base.title, displayNumber = null, continuationOf = base.id,
            description = delivery.text, status = MilestoneStatus.PENDING, attempts = emptyList(), report = "", checkNote = "", dependsOn = listOf(base.id))
        if (generated.copy(displayNumber = null) != expected || coordination.any { it.stageId == generatedId } ||
            milestones.any { it.id != generatedId && generatedId in it.dependsOn } ||
            tree.any { generatedId in it.dependsOn || (it.kind != DecisionKind.GOAL && generatedId in it.children) } ||
            tree.any { it.id == generatedId && it != DecisionNode(generatedId, generated.title, DecisionKind.STAGE, stageId = generatedId) } ||
            deliveries.any { it.id !in cancelledIds && it.targetStageId == generatedId } ||
            scheduledMessages.any { it.targetTaskId == generatedId || it.waitTaskId == generatedId } ||
            proposal?.milestones?.any { it.id == generatedId } == true) null else generatedId
    }.toSet()
    return copy(deliveries = deliveries.map { if (it.id in cancelledIds && it.state == DeliveryState.QUEUED) it.copy(state = DeliveryState.CANCELLED) else it },
        milestones = milestones.filterNot { it.id in removable }.map { stage ->
            val attempt = stage.attempts.lastOrNull()
            if (attempt != null && commands.any { it.first.targetStageId == stage.id } &&
                canRestorePeerVerification(stage, attempt, cancelledIds, admittedAttempts[attempt.sessionId]))
                stage.copy(attempts = stage.attempts.dropLast(1) + attempt.copy(phase = AttemptPhase.VERIFYING)) else stage
        },
        tree = tree.filterNot { it.id in removable }.map { it.copy(children = it.children.filterNot { id -> id in removable }) })
}

/** Restore the application's durable VERIFY decision only when the synthetic continuation was never admitted.
 * Native ownership, quarantine, errors and generation remain intact for the normal recovery proof. */
private fun Plan.canRestorePeerVerification(stage: Milestone, attempt: StageAttempt, cancelledIds: Set<String>, admitted: SessionLegacyAttempt?): Boolean {
    if (attempt.phase != AttemptPhase.EXECUTING || attempt.turnIndex <= 0 || attempt.awaitingPlanner || attempt.coordinationPending == true ||
        attempt.waitingForUser != null || attempt.waitingForEvent != null || attempt.pendingTool.isNotBlank() || attempt.pendingToolExternal ||
        attempt.mergePhase != null || attempt.acceptanceRecord != null || attempt.sessionGeneration <= 0) return false
    val previousTurn = attempt.turnIndex - 1
    if (admitted != SessionLegacyAttempt(id, runId, stage.id, attempt.id, previousTurn, attempt.sessionGeneration)) return false
    val record = coordination.singleOrNull { it.id == "${attempt.id}-turn-$previousTurn" && it.runId == runId &&
        it.stageId == stage.id && it.attemptId == attempt.id && it.sourceSessionId == attempt.sessionId && it.turnIndex == previousTurn }
        ?: return false
    val decision = record.decision ?: return false
    if (record.status != HandoffStatus.RESOLVED || record.reply.kind != StageReplyKind.RESULT || record.reply.text.isBlank() ||
        record.nextStep != "Исполнителю назначено продолжение" || record.verification != null ||
        decision.resultAction != CoordinatorResultAction.VERIFY || decision.actions.isNotEmpty() || decision.askUser || decision.replan ||
        decision.questions.isNotEmpty() || decision.sessionActions.isNotEmpty() || decision.schedules.isNotEmpty() ||
        attempt.report != record.reply.text || decision.resultProblem(record) != null) return false
    if (coordination.any { it.stageId == stage.id && it.runId == runId && it.attemptId == attempt.id && it.turnIndex >= attempt.turnIndex }) return false
    if (deliveries.any { it.targetStageId == stage.id && it.id !in cancelledIds &&
            (it.state in setOf(DeliveryState.QUEUED, DeliveryState.DELIVERED) ||
                it.state == DeliveryState.ANSWERED && it.attemptId == attempt.id && it.turnIndex >= attempt.turnIndex) }) return false
    if (attempt.chatTurns.size !in attempt.turnIndex..attempt.turnIndex + 1 ||
        attempt.chatTurns.getOrNull(previousTurn)?.completedAt?.let { it > 0 } != true) return false
    val prepared = attempt.chatTurns.getOrNull(attempt.turnIndex)
    return prepared == null || prepared.completedAt == 0L && prepared.startStep == attempt.steps.count { it.isVisibleActivity }
}
