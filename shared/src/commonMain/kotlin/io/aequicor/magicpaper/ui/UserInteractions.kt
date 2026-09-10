package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*

internal fun approvalInteraction(approval: CodingApproval, sessions: List<CodingSession>): UserInteractionRequest {
    val source = sessions.firstOrNull { approval.sessionId in setOf(it.id, "${it.id}-merge", "${it.id}-delivery") }
    val owner = source?.parentSessionId ?: source?.id ?: approval.sessionId
    val failed = approval.error != null
    return UserInteractionRequest(
        id = "approval:${approval.id}" + if (failed) ":error" else "", projectId = approval.projectId,
        sessionId = source?.id ?: approval.sessionId, ownerSessionId = owner,
        affectedSessionIds = setOfNotNull(source?.id, owner), kind = InteractionKind.APPROVAL, sourceId = approval.id,
        context = approval.sessionName, details = approval.reason + "\n\n" + approval.details +
            if (approval.kind == CodingApprovalKind.PERMISSIONS) "\n\nДоступ действует до завершения текущего запроса." else "",
        questions = listOf(PlanningQuestion("decision", if (failed) "Передача решения не подтверждена. Остановить запрос?" else approval.title,
            QuestionKind.SINGLE, if (failed) listOf(QuestionOption("stop", "Остановить запрос"))
            else listOf(QuestionOption("yes", "Да", enabled = approval.canAllow), QuestionOption("no", "Нет")),
            allowCustomInput = false, canSkip = false)),
        submitting = approval.submitting && !failed, error = approval.error, outcomeUnknown = failed,
    )
}

internal fun interactionCandidates(
    ui: CodingUi,
    plans: List<Plan>,
    states: Map<String, OrchestrationState>,
    persistenceErrors: Map<String, String>,
    runtime: List<UserInteractionRequest> = emptyList(),
    requestedInputRecovery: Set<String> = emptySet(),
): List<UserInteractionRequest> = buildList {
    val sessions = ui.sessions.map { it.session }
    val replacedPlanIds = plans.mapNotNull { it.replacesPlanId }.toSet()
    addAll(runtime.map { request ->
        val source = sessions.firstOrNull { request.sessionId in setOf(it.id, "${it.id}-merge", "${it.id}-delivery") }
        if (source == null) request else request.copy(sessionId = source.id, ownerSessionId = source.id,
            affectedSessionIds = setOf(source.id))
    }.filter { request -> sessions.none { it.id == request.sessionId && it.archived } })
    addAll(ui.approvals.map { approvalInteraction(it, sessions) })
    states.values.forEach { state ->
        val parent = sessions.firstOrNull { it.id == state.sessionId && !it.archived } ?: return@forEach
        state.openQuestions().filter { q -> q.planId !in replacedPlanIds && plans.any { it.id == q.planId } }.forEach { q ->
            val workers = sessions.filter { q.refinementRequest == null && !q.forDiscussion && it.parentSessionId == parent.id && it.planId == q.planId &&
                (q.stageIds.isEmpty() || it.stageId in q.stageIds || it.id == q.sourceSessionId) }.map { it.id }
            add(UserInteractionRequest("question:${q.id}", parent.projectId, q.sourceSessionId, InteractionKind.QUESTION,
                q.questions, sourceId = q.id, ownerSessionId = parent.id, affectedSessionIds = (workers + parent.id + q.sourceSessionId).toSet(),
                context = listOf(sessions.firstOrNull { it.id == q.sourceSessionId }?.name ?: parent.name, q.scopeLabel).filter { it.isNotBlank() }.joinToString(" · "),
                planId = q.planId, initialAnswers = q.partialAnswers,
                createdAt = ui.sessions.firstOrNull { it.session.id == parent.id }?.messages?.firstOrNull { it.id == q.id }?.createdAt ?: 0))
        }
        state.inputs.filter { it.status == OrchestrationInputStatus.FAILED ||
            it.status == OrchestrationInputStatus.CANCELLED && it.id in requestedInputRecovery }.forEach { input ->
            add(recoveryInteraction("input:${parent.id}:${input.id}:${input.attempt}:${input.error.hashCode()}", parent, InteractionKind.RECOVER_INPUT,
                "Не удалось обработать сообщение. Как продолжить?", input.error, "Повторить обработку", sourceId = input.id))
        }
    }
    plans.forEach { plan ->
        if (plan.id in replacedPlanIds) return@forEach
        val parentUi = ui.sessions.firstOrNull { it.session.id == plan.parentSessionId && !it.session.archived } ?: return@forEach
        val parent = parentUi.session
        val history = parentUi.messages
        // Legacy serialized questions are supported even before the orchestration migration has run.
        val answered = history.filter { it.planning?.closesRequest != false }.mapNotNull { it.planning?.replyTo }.toSet()
        history.filter { it.planning?.planId == plan.id && it.planning.questions.isNotEmpty() &&
            it.planning.requestStatus == UserRequestStatus.OPEN && it.id !in answered }.forEach { message ->
            if (none { it.id == "question:${message.id}" }) {
                val block = message.planning!!
                val affected = sessions.filter { it.parentSessionId == parent.id && it.planId == plan.id &&
                    (block.affectedStageIds.isEmpty() && block.sourceStageId == null || it.stageId in block.affectedStageIds || it.stageId == block.sourceStageId) }
                add(UserInteractionRequest("question:${message.id}", parent.projectId, block.sourceSessionId ?: parent.id,
                    InteractionKind.QUESTION, block.questions, sourceId = message.id, ownerSessionId = parent.id,
                    affectedSessionIds = (affected.map { it.id } + parent.id).toSet(), context = block.scopeLabel,
                    planId = plan.id, createdAt = message.createdAt))
            }
        }
        val blockers = plan.blockingIssues(history)
        if (blockers.isNotEmpty()) {
            val action = blockers.recoveryActionLabel()
            val affected = sessions.filter { it.parentSessionId == parent.id && it.planId == plan.id &&
                blockers.any { b -> b.stage == null || b.stage.id == it.stageId } }.map { it.id }
            add(recoveryInteraction("blocker:${blockers.map { it.messageId }.sorted().joinToString(":")}", parent,
                InteractionKind.RECOVER_PLAN, "Выполнение остановлено. Как продолжить?", blockers.joinToString("\n\n") { it.text }, action,
                sourceId = plan.id, allowSkipVerification = blockers.all { it.canSkipVerification }, allowRetry = blockers.none { it.issue.retryBlocked })
                .copy(planId = plan.id, affectedSessionIds = (affected + parent.id).toSet()))
        }
        val pendingQuestions = any { it.kind == InteractionKind.QUESTION && it.planId == plan.id }
        val initialReady = plan.confirmedRevision == null && plan.wizardStep != PlanningStep.CLARIFY &&
            plan.selectedMilestones.isNotEmpty() && DecisionCompiler.compile(plan).valid && !parentUi.running
        if (!pendingQuestions && (plan.proposalReadyForConfirmation || initialReady)) {
            val proposal = plan.proposal.takeIf { plan.proposalReadyForConfirmation }
            val stages = proposal?.milestones ?: plan.selectedMilestones
            val details = (proposal?.explanation ?: plan.goal) + "\n\n" + stages.joinToString("\n\n") {
                "${it.stageLabel()}\n${it.description}\nКритерии: ${it.acceptance}" }
            add(UserInteractionRequest("confirm:${plan.id}:${proposal?.id ?: (plan.tree.hashCode().toString() + ":" + plan.milestones.specification().hashCode())}", parent.projectId, parent.id,
                InteractionKind.CONFIRM_PLAN, listOf(PlanningQuestion("decision", if (proposal != null) "Запустить предложенную доработку?" else "Подтвердить и запустить план?",
                    QuestionKind.SINGLE, listOf(QuestionOption("yes", "Да"), QuestionOption("no", "Нет")), allowCustomInput = false, canSkip = false)),
                sourceId = proposal?.id.orEmpty(), context = parent.name, details = details, planId = plan.id,
                revision = plan.revision, createdAt = plan.updatedAt))
        }
    }
    persistenceErrors.forEach { (sessionId, error) ->
        sessions.firstOrNull { it.id == sessionId }?.let { session ->
            add(recoveryInteraction("storage:$sessionId:${error.hashCode()}", session, InteractionKind.RECOVER_STORAGE,
                "Не удалось сохранить работу. Как продолжить?", error, "Проверить хранилище и восстановить очередь"))
        }
    }
    ui.sessions.filter { !it.session.archived && !it.running && it.plan == null && !it.session.planningMode && it.session.stageId == null }.forEach { item ->
        val checkpoint = item.session.pendingRun
        // History can arrive before checkpoint cleanup, including after a restart.
        if (checkpoint?.hasSuccessfulResponse(item.messages) == true) return@forEach
        val interrupted = item.messages.interruptedCodingRequest()
        if (checkpoint?.stoppedByUser == true || checkpoint?.intent == ExecutionIntent.PAUSE || checkpoint == null && interrupted == null) return@forEach
        val source = checkpoint?.responseId?.ifBlank { checkpoint.messageId } ?: interrupted!!.id
        add(recoveryInteraction("run:${item.session.id}:$source", item.session, InteractionKind.RECOVER_RUN,
            "Выполнение остановлено. Как продолжить?", item.messages.lastOrNull { it.failed }?.text ?: "Предыдущий запрос не завершён.",
            "Повторить запуск", sourceId = source))
    }
}

private fun recoveryInteraction(id: String, session: CodingSession, kind: InteractionKind, title: String, details: String,
    retry: String, sourceId: String = id, allowSkipVerification: Boolean = false, allowRetry: Boolean = true) = UserInteractionRequest(id, session.projectId, session.id, kind,
    listOf(PlanningQuestion("decision", title, QuestionKind.SINGLE,
        buildList {
            if (allowRetry) add(QuestionOption("retry", retry)) else add(QuestionOption("review", "Разобрать с оркестратором"))
            if (allowSkipVerification) add(QuestionOption("skip_verification", "Продолжить без проверки"))
            add(QuestionOption("leave", "Оставить остановленной"))
        })),
    sourceId = sourceId, context = session.name, details = details)
