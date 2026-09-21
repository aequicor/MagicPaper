package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.planning.OrchestrationEvent
import io.aequicor.magicpaper.domain.planning.OrchestrationTransition
import io.aequicor.magicpaper.domain.planning.reduce
import io.aequicor.magicpaper.machine.Machine
import io.aequicor.magicpaper.machine.MachineId
import io.aequicor.magicpaper.machine.Step
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Project/session/history authority. Child journals retain process, organism and workspace authority. */
object CodingMachine : Machine<CodingMachine.State, CodingMachine.Input, CodingMachine.Effect> {
    override val id = MachineId("coding")
    override val space get() = CodingSpace
    /** Bridge to the owner's own reducer: [Transition] and [reduce] keep every call site. */
    override fun step(state: State, input: Input) = reduce(state, input).let { Step(it.state, it.effects) }

    @Serializable data class SessionRef(val id: String, val generation: Long)
    @Serializable data class RunRef(val session: SessionRef, val requestId: String, val generation: Long,
        val messageId: String, val responseId: String, val timelineId: String)
    @Serializable data class ChildRevision(val stream: String, val seq: Long, val resetEpoch: Long, val inputId: String)
    @Serializable enum class Phase { RUNNING, STOPPING, INTERRUPTED, UNKNOWN, ABANDONING }
    @ConsistentCopyVisibility data class Run internal constructor(val ref: RunRef, val phase: Phase, val abandonDecisionId: String? = null, val abandonAttempt: NativeRunRecoveryRef? = null, val executionGeneration: Long = ref.session.generation, val knownStopped: Boolean = false, val abandonNoDispatchProof: NativeRunNoDispatchProof? = null)
    @ConsistentCopyVisibility data class State internal constructor(
        val project: CodingProject? = null,
        val sessions: Map<String, CodingSession> = emptyMap(),
        val histories: Map<String, List<CodingMessage>> = emptyMap(),
        val removedMessages: Map<String, Set<String>> = emptyMap(),
        val removedSessions: Set<String> = emptySet(),
        val runs: Map<String, Run> = emptyMap(),
        val generations: Map<String, Long> = emptyMap(),
        val startedRequests: Set<String> = emptySet(),
        val acknowledgements: Map<String, NativeRunRecoveryAcknowledgement> = emptyMap(),
        val noDispatchAcknowledgements: Map<String, NativeRunNoDispatchAcknowledgement> = emptyMap(),
        val orchestrations: Map<String, OrchestrationState> = emptyMap(),
        val childRevisions: Map<String, ChildRevision> = emptyMap(),
        val unknownChildren: Set<String> = emptySet(),
        val titleRequests: Map<String, String> = emptyMap(),
        val deleted: Boolean = false,
        val persistenceUnknown: Boolean = false,
    ) {
        val initialized: Boolean get() = project != null
        fun session(ref: SessionRef): CodingSession? = sessions[ref.id]?.takeIf { it.runtimeGeneration == ref.generation }
        fun run(ref: RunRef): Run? = runs[ref.session.id]?.takeIf { it.ref == ref }
    }

    @Serializable sealed interface Input
    @Serializable sealed interface Intent : Input {
        @Serializable @SerialName("CreateProject") data class CreateProject(val project: CodingProject) : Intent
        @Serializable @SerialName("SetProjectModel") data class SetProjectModel(val selection: ModelSelection?) : Intent
        @Serializable @SerialName("DeleteProject") data object DeleteProject : Intent
        @Serializable @SerialName("CreateSession") data class CreateSession(val session: CodingSession,
            val messages: List<CodingMessage> = emptyList()) : Intent
        @Serializable @SerialName("DeleteSession") data class DeleteSession(val session: SessionRef) : Intent
        @Serializable @SerialName("RenameSession") data class RenameSession(val session: SessionRef, val name: String,
            val expectedName: String? = null) : Intent
        @Serializable @SerialName("ArchiveSession") data class ArchiveSession(val session: SessionRef, val archived: Boolean, val at: Long? = null) : Intent
        @Serializable @SerialName("SetSessionModel") data class SetSessionModel(val session: SessionRef,
            val selection: ModelSelection?) : Intent
        @Serializable @SerialName("SetSearchProvider") data class SetSearchProvider(val session: SessionRef, val provider: SearchProvider) : Intent
        @Serializable @SerialName("ChangeMode") data class ChangeMode(val session: SessionRef, val mode: CodingInteractionMode) : Intent
        @Serializable @SerialName("SetMediaTool") data class SetMediaTool(val session: SessionRef, val kind: MediaKind, val enabled: Boolean) : Intent
        @Serializable @SerialName("SetFeatureFlag") data class SetFeatureFlag(val session: SessionRef, val flag: FeatureFlag, val enabled: Boolean) : Intent
        @Serializable @SerialName("SetWorktreeEnabled") data class SetWorktreeEnabled(val session: SessionRef, val enabled: Boolean) : Intent
        @Serializable @SerialName("VerifyResponse") data class VerifyResponse(val session: SessionRef, val responseId: String, val verified: Boolean) : Intent
        @Serializable @SerialName("UnlinkProfile") data class UnlinkProfile(val profileId: String) : Intent
        @Serializable @SerialName("Enqueue") data class Enqueue(val session: SessionRef, val request: CodingRunCheckpoint) : Intent
        @Serializable @SerialName("QueuedClarified") data class QueuedClarified(val session: SessionRef,
            val requestId: String, val note: CodingMessage, val attachments: List<Attachment>) : Intent
        @Serializable @SerialName("BeginRun") data class BeginRun(val session: SessionRef, val request: CodingRunCheckpoint,
            val at: Long, val localSummaryAllowed: Boolean = false) : Intent
        @Serializable @SerialName("BeginRepair") data class BeginRepair(val ref: RunRef, val requestId: String,
            val workspaceRevision: ChildRevision) : Intent
        @Serializable @SerialName("Pause") data class Pause(val ref: RunRef) : Intent
        @Serializable @SerialName("DeferRecovery") data class DeferRecovery(val session: SessionRef, val messageId: String) : Intent
        @Serializable @SerialName("Clarify") data class Clarify(val ref: RunRef, val request: CodingRunCheckpoint,
            val message: CodingMessage) : Intent
        @Serializable @SerialName("Abandon") data class Abandon(val ref: RunRef, val decisionId: String, val previous: NativeRunRecoveryRef) : Intent
        @Serializable @SerialName("AbandonNotDispatched") data class AbandonNotDispatched(val ref: RunRef, val decisionId: String, val proof: NativeRunNoDispatchProof) : Intent
        @Serializable @SerialName("DiscardInterrupted") data class DiscardInterrupted(val ref: RunRef) : Intent
        @Serializable @SerialName("EditRequest") data class EditRequest(val session: SessionRef,
            val expected: List<CodingMessage>, val messages: List<CodingMessage>, val request: CodingRunCheckpoint) : Intent
        @Serializable @SerialName("ReplaceHistory") data class ReplaceHistory(val session: SessionRef,
            val expected: List<CodingMessage>, val messages: List<CodingMessage>) : Intent
        @Serializable @SerialName("Orchestrate") data class Orchestrate(val session: SessionRef,
            val event: OrchestrationEvent) : Intent
    }
    @Serializable sealed interface Fact : Input {
        @Serializable @SerialName("LegacyImported") data class LegacyImported(val project: CodingProject,
            val sessions: List<CodingSession>, val histories: Map<String, List<CodingMessage>>,
            val removedMessages: Map<String, Set<String>> = emptyMap(),
            val orchestrations: List<OrchestrationState> = emptyList()) : Fact
        @Serializable @SerialName("HistoryPublished") data class HistoryPublished(val session: SessionRef,
            val messages: List<CodingMessage>) : Fact
        @Serializable @SerialName("StatusObserved") data class StatusObserved(val session: SessionRef,
            val status: CodingSessionStatus, val at: Long) : Fact
        @Serializable @SerialName("ArchiveReadinessObserved") data class ArchiveReadinessObserved(val session: SessionRef,
            val expected: CodingSession, val ready: Boolean, val at: Long, val delay: Long) : Fact
        @Serializable @SerialName("NativeSessionBound") data class NativeSessionBound(val ref: RunRef, val nativeSessionId: String) : Fact
        @Serializable @SerialName("RunFinished") data class RunFinished(val ref: RunRef, val response: CodingMessage,
            val outcomeKnown: Boolean = true) : Fact
        @Serializable @SerialName("RunOutputPublished") data class RunOutputPublished(val ref: RunRef, val message: CodingMessage) : Fact
        @Serializable @SerialName("RunStopped") data class RunStopped(val ref: RunRef, val unknown: Boolean) : Fact
        @Serializable @SerialName("AbandonAcknowledged") data class AbandonAcknowledged(val ref: RunRef,
            val acknowledgement: NativeRunRecoveryAcknowledgement) : Fact
        @Serializable @SerialName("NoDispatchAcknowledged") data class NoDispatchAcknowledged(val ref: RunRef,
            val acknowledgement: NativeRunNoDispatchAcknowledgement) : Fact
        @Serializable @SerialName("RecoveryAcknowledgementConsumed") data class RecoveryAcknowledgementConsumed(val ref: RunRef,
            val proof: NativeRunRecoveryConsumption) : Fact
        @Serializable @SerialName("TitleRequested") data class TitleRequested(val session: SessionRef, val requestId: String) : Fact
        @Serializable @SerialName("PromptNamed") data class PromptNamed(val session: SessionRef, val prompt: String, val localSummaryAllowed: Boolean) : Fact
        @Serializable @SerialName("TitleObserved") data class TitleObserved(val session: SessionRef,
            val requestId: String, val title: String) : Fact
        @Serializable @SerialName("PlanSessionBound") data class PlanSessionBound(val session: SessionRef,
            val role: CodingSessionRole, val planId: String? = null, val stageId: String? = null,
            val stageNumber: Int? = null, val orchestratorNumber: Int? = null, val continuationOfNumber: Int? = null,
            val suggestedName: String? = null, val legacyName: String? = null) : Fact
        @Serializable @SerialName("OrganismProjected") data class OrganismProjected(val organism: SessionOrganism,
            val revision: ChildRevision) : Fact
        @Serializable @SerialName("WorktreeProjected") data class WorktreeProjected(val session: SessionRef,
            val task: TaskWorktree?, val revision: ChildRevision, val unknown: Boolean = false) : Fact
        @Serializable @SerialName("WorktreeUnavailable") data class WorktreeUnavailable(val ref: RunRef) : Fact
        @Serializable @SerialName("Restored") data object Restored : Fact
        @Serializable @SerialName("PersistenceUnknown") data object PersistenceUnknown : Fact
    }
    sealed interface Effect {
        data class RunRequest(val ref: RunRef, val request: CodingRunCheckpoint,
            val previousAcknowledgement: NativeRunRecoveryAcknowledgement?,
            val previousNoDispatchAcknowledgement: NativeRunNoDispatchAcknowledgement? = null) : Effect
        data class AbortRequest(val ref: RunRef) : Effect
        data class AcknowledgePrevious(val ref: RunRef, val decisionId: String) : Effect
        data class AcknowledgeNotDispatched(val ref: RunRef, val decisionId: String, val proof: NativeRunNoDispatchProof) : Effect
        data class SessionCreated(val session: CodingSession) : Effect
        data class CleanupDeleted(val sessionIds: Set<String>) : Effect
        data class Orchestrated(val transition: OrchestrationTransition) : Effect
        data class Reject(val reason: String) : Effect
    }
    data class Transition(val state: State, val effects: List<Effect> = emptyList())
    fun initial(): State = State()
    fun ref(session: CodingSession) = SessionRef(session.id, session.runtimeGeneration)

    fun reduce(state: State, input: Input): Transition {
        fun reject(reason: String) = Transition(state, listOf(Effect.Reject(reason)))
        if (input == Fact.PersistenceUnknown) return Transition(state.copy(persistenceUnknown = true))
        if (state.persistenceUnknown) return reject("Не удалось подтвердить сохранение проекта. Восстановите данные.")
        if (input == Fact.Restored) return Transition(state.copy(runs = state.runs.mapValues { (_, run) ->
            if (run.phase in setOf(Phase.RUNNING, Phase.STOPPING, Phase.ABANDONING)) run.copy(phase = Phase.UNKNOWN, knownStopped = false) else run
        }))
        if (state.deleted) return reject("Проект удалён")
        if (!state.initialized) return when (input) {
            is Intent.CreateProject -> if (input.project.id.isBlank()) reject("Не указан проект") else Transition(state.copy(project = input.project))
            is Fact.LegacyImported -> importLegacy(state, input)
            else -> reject("Проект ещё не восстановлен")
        }
        return try { when (input) {
            is Intent.CreateProject, is Fact.LegacyImported -> reject("Проект уже существует")
            is Intent.SetProjectModel -> Transition(state.copy(project = state.project!!.copy(modelSelection = input.selection)))
            Intent.DeleteProject -> Transition(state.copy(deleted = true, sessions = emptyMap(), histories = emptyMap(),
                runs = emptyMap(), removedSessions = state.removedSessions + state.sessions.keys), listOf(Effect.CleanupDeleted(state.sessions.keys)))
            is Intent.CreateSession -> createSession(state, input)
            is Intent.DeleteSession -> {
                val session = requireSession(state, input.session)
                Transition(state.copy(sessions = state.sessions - session.id, histories = state.histories - session.id,
                    runs = state.runs - session.id, orchestrations = state.orchestrations - session.id,
                    removedSessions = state.removedSessions + session.id), listOf(Effect.CleanupDeleted(setOf(session.id))))
            }
            is Intent.RenameSession -> change(state, input.session) {
                require(input.name.isNotBlank()) { "Добавьте название" }
                require(input.expectedName == null || it.name == input.expectedName || it.name == input.name.trim()) { "Название уже изменено" }
                it.copy(name = input.name.trim(), nameManuallySet = true)
            }
            is Intent.ArchiveSession -> change(state, input.session) {
                require(!input.archived || state.runs[it.id]?.phase !in setOf(Phase.RUNNING, Phase.STOPPING)) { "Сначала остановите работу" }
                it.copy(archived = input.archived, archiveReadySince = if (!input.archived) input.at ?: it.archiveReadySince else it.archiveReadySince)
            }
            is Intent.SetSessionModel -> change(state, input.session) { it.copy(modelSelection = input.selection, llmProfileId = input.selection?.profileId) }
            is Intent.SetSearchProvider -> change(state, input.session) { it.copy(searchProvider = input.provider) }
            is Intent.ChangeMode -> change(state, input.session) { it.changeInteractionMode(input.mode,
                state.runs[it.id] != null || it.queuedPrompts.isNotEmpty()) }
            is Intent.SetMediaTool -> change(state, input.session) { it.copy(mediaTools = it.mediaTools.withEnabled(input.kind, input.enabled)) }
            is Intent.SetFeatureFlag -> change(state, input.session) { it.copy(featureFlags = it.featureFlags.with(input.flag, input.enabled)) }
            is Intent.SetWorktreeEnabled -> change(state, input.session) {
                require(state.runs[it.id] == null && it.queuedPrompts.isEmpty() && it.taskWorktree?.phase.let { phase -> phase == null || phase == TaskWorktreePhase.COMPLETE }) { "Сначала завершите текущую задачу" }
                it.copy(worktreeEnabled = input.enabled)
            }
            is Intent.VerifyResponse -> change(state, input.session) {
                val last = state.histories[it.id].orEmpty().lastOrNull { m -> m.role == CodingRole.AGENT && !m.systemContext && !m.systemNotice }
                require(last?.id == input.responseId && !last.failed && state.runs[it.id] == null) { "Результат уже изменился" }
                it.copy(manuallyVerifiedResponseId = if (input.verified) input.responseId else null)
            }
            is Intent.UnlinkProfile -> Transition(state.copy(project = state.project!!.let { if (it.modelSelection?.profileId == input.profileId) it.copy(modelSelection = null) else it },
                sessions = state.sessions.mapValues { (_, s) -> s.copy(llmProfileId = s.llmProfileId.takeUnless { it == input.profileId },
                    modelSelection = s.modelSelection?.takeUnless { it.profileId == input.profileId }) }))
            is Intent.Enqueue -> change(state, input.session) {
                require(!it.archived && (input.request.interactionMode == null || input.request.interactionMode == it.interactionMode)) { "Сессия изменилась" }
                validateRequest(state, it, input.request, queued = false)
                it.copy(queuedPrompts = it.queuedPrompts + input.request)
            }
            is Intent.QueuedClarified -> {
                val session = requireSession(state, input.session)
                val request = requireNotNull(session.queuedPrompts.singleOrNull { it.runId == input.requestId }) { "Запрос уже не в очереди" }
                require(input.requestId !in state.startedRequests && input.note.role == CodingRole.USER && input.note.id.isNotBlank())
                val reserved = state.histories[session.id].orEmpty().map { it.id } + state.removedMessages[session.id].orEmpty() +
                    session.queuedPrompts.flatMap { listOf(it.messageId, it.responseId, it.responseTimelineId) }
                require(input.note.id !in reserved) { "Идентификатор уточнения уже занят" }
                val revised = request.copy(prompt = request.prompt + if (input.note.text.isBlank()) "" else "\n\nУточнение пользователя: ${input.note.text}",
                    attachments = (request.attachments + input.attachments).distinctBy { it.id })
                val next = publishHistory(state, input.session, listOf(input.note))
                Transition(next.copy(sessions = next.sessions + (session.id to session.copy(
                    queuedPrompts = session.queuedPrompts.map { if (it.runId == input.requestId) revised else it }))))
            }
            is Intent.BeginRun -> begin(state, input)
            is Intent.BeginRepair -> {
                val run = requireRun(state, input.ref)
                val session = requireRunSession(state, input.ref)
                require("workspace:${session.id}" !in state.unknownChildren && run.phase == Phase.RUNNING && session.taskWorktree?.phase == TaskWorktreePhase.CONFLICT &&
                    state.childRevisions["workspace:${session.id}"] == input.workspaceRevision) { "Исправление конфликта уже недоступно" }
                require(input.requestId.isNotBlank() && input.requestId !in state.startedRequests) { "Запрос уже выполнялся" }
                val request = requireNotNull(session.pendingRun).copy(runId = input.requestId)
                val ref = run.ref.copy(requestId = input.requestId, generation = run.ref.generation + 1)
                val next = state.copy(sessions = state.sessions + (session.id to session.copy(pendingRun = request)),
                    runs = state.runs + (session.id to run.copy(ref = ref)), generations = state.generations + (session.id to ref.generation),
                    startedRequests = state.startedRequests + input.requestId)
                Transition(next, listOf(Effect.RunRequest(ref, request, state.acknowledgements[session.id], state.noDispatchAcknowledgements[session.id])))
            }
            is Intent.DeferRecovery -> {
                val session = requireSession(state, input.session)
                val run = state.runs[session.id]
                require(run?.phase !in setOf(Phase.RUNNING, Phase.STOPPING, Phase.ABANDONING)) { "Сначала остановите работу" }
                val request = session.pendingRun ?: run {
                    require(run == null && session.queuedPrompts.isEmpty()) { "Сохранённый запрос изменился" }
                    val message = requireNotNull(state.histories[session.id].orEmpty().interruptedCodingRequest()
                        ?.takeIf { it.id == input.messageId }) { "Сохранённый запрос изменился" }
                    CodingRunCheckpoint(message.id, message.text, message.inputAttachments, interactionMode = session.interactionMode)
                }
                require(request.messageId == input.messageId) { "Сохранённый запрос изменился" }
                val generation = run?.ref?.generation ?: (state.generations[session.id] ?: 0) + 1
                // User deferral records no proof about the old external outcome, including legacy history.
                val deferred = run ?: Run(RunRef(input.session, request.runId, generation,
                    request.messageId, request.responseId, request.responseTimelineId), Phase.UNKNOWN)
                Transition(state.copy(sessions = state.sessions + (session.id to session.copy(pendingRun =
                    request.copy(intent = ExecutionIntent.STOP, stoppedByUser = true))),
                    runs = state.runs + (session.id to deferred), generations = state.generations + (session.id to generation),
                    startedRequests = state.startedRequests + request.runId))
            }
            is Intent.Pause -> {
                val run = requireRun(state, input.ref)
                val changed = changeRun(state, input.ref) { it.copy(pendingRun = it.pendingRun?.copy(intent = ExecutionIntent.STOP, stoppedByUser = true)) }.state
                Transition(changed.copy(runs = changed.runs + (input.ref.session.id to run.copy(phase = if (run.phase == Phase.RUNNING) Phase.STOPPING else run.phase))),
                    if (run.phase == Phase.RUNNING) listOf(Effect.AbortRequest(input.ref)) else emptyList())
            }
            is Intent.Clarify -> {
                val run = requireRun(state, input.ref)
                val session = requireRunSession(state, input.ref)
                validateRequest(state, session, input.request, queued = false)
                require(input.message.role == CodingRole.USER && input.message.id == input.request.messageId) { "Неверное уточнение" }
                val changed = publishHistory(state, ref(requireRunSession(state, input.ref)), listOf(input.message)).copy(sessions = state.sessions + (session.id to session.copy(
                    queuedPrompts = listOf(input.request) + session.queuedPrompts,
                    pendingRun = session.pendingRun?.copy(intent = ExecutionIntent.STOP))))
                Transition(changed.copy(runs = changed.runs + (session.id to run.copy(phase = if (run.phase == Phase.RUNNING) Phase.STOPPING else run.phase))),
                    if (run.phase == Phase.RUNNING) listOf(Effect.AbortRequest(input.ref)) else emptyList())
            }
            is Intent.Abandon -> {
                val run = requireRun(state, input.ref)
                require(input.decisionId.isNotBlank() && input.previous.sessionId == run.ref.session.id &&
                    input.previous.requestId == run.ref.requestId && input.previous.engine == requireRunSession(state, input.ref).engine &&
                    input.previous.attempt >= 0 && run.phase in setOf(Phase.UNKNOWN, Phase.INTERRUPTED)) { "Сначала остановите работу" }
                Transition(state.copy(runs = state.runs + (input.ref.session.id to run.copy(phase = Phase.ABANDONING, abandonDecisionId = input.decisionId, abandonAttempt = input.previous, abandonNoDispatchProof = null))), listOf(Effect.AcknowledgePrevious(input.ref, input.decisionId)))
            }
            is Intent.AbandonNotDispatched -> {
                val run = requireRun(state, input.ref)
                val session = requireRunSession(state, input.ref)
                require(input.decisionId.isNotBlank() && input.proof.proofId.isNotBlank() && input.proof.journalGeneration.isNotBlank() &&
                    input.proof.engine == session.engine && input.proof.sessionId == session.id && input.proof.requestId == input.ref.requestId &&
                    run.phase in setOf(Phase.UNKNOWN, Phase.INTERRUPTED)) { "Нет точного подтверждения, что запрос не был отправлен" }
                Transition(state.copy(runs = state.runs + (session.id to run.copy(phase = Phase.ABANDONING,
                    abandonDecisionId = input.decisionId, abandonAttempt = null, abandonNoDispatchProof = input.proof))),
                    listOf(Effect.AcknowledgeNotDispatched(input.ref, input.decisionId, input.proof)))
            }
            is Intent.DiscardInterrupted -> {
                require(requireRun(state, input.ref).phase == Phase.INTERRUPTED) { "Исход предыдущего запроса неизвестен" }
                val next = changeRun(state, input.ref) { it.copy(pendingRun = null) }.state
                Transition(next.copy(runs = next.runs - input.ref.session.id))
            }
            is Intent.EditRequest -> {
                val session = requireSession(state, input.session)
                validateRequest(state, session, input.request, queued = false)
                require(input.messages.lastOrNull()?.let { it.id == input.request.messageId && it.role == CodingRole.USER &&
                    it.text == input.request.prompt && it.inputAttachments == input.request.attachments } == true) { "Изменённый запрос не совпадает с историей" }
                val rewritten = reduce(state, Intent.ReplaceHistory(input.session, input.expected, input.messages))
                require(rewritten.effects.none { it is Effect.Reject }) { "История изменилась. Повторите действие." }
                val changed = rewritten.state.sessions.getValue(session.id).copy(queuedPrompts = listOf(input.request))
                Transition(rewritten.state.copy(sessions = rewritten.state.sessions + (session.id to changed)))
            }
            is Intent.ReplaceHistory -> {
                val session = requireSession(state, input.session)
                require(state.runs[session.id] == null && session.queuedPrompts.isEmpty()) { "Сначала завершите текущий запрос" }
                require(state.histories[session.id].orEmpty() == input.expected) { "История изменилась. Повторите действие." }
                validateMessages(input.messages)
                val removed = input.expected.map { it.id }.toSet() - input.messages.map { it.id }.toSet()
                require(input.messages.none { it.id in state.removedMessages[session.id].orEmpty() }) { "Сообщение уже удалено" }
                Transition(state.copy(sessions = state.sessions + (session.id to session.copy(piSessionId = "", needsHistorySeed = true, manuallyVerifiedResponseId = null)),
                    histories = state.histories + (session.id to input.messages),
                    removedMessages = state.removedMessages + (session.id to (state.removedMessages[session.id].orEmpty() + removed))))
            }
            is Intent.Orchestrate -> {
                val session = requireSession(state, input.session)
                val transition = reduce(state.orchestrations[session.id] ?: OrchestrationState(session.id, session.projectId), input.event)
                Transition(state.copy(orchestrations = state.orchestrations + (session.id to transition.state)), listOf(Effect.Orchestrated(transition)))
            }
            is Fact.HistoryPublished -> Transition(publishHistory(state, input.session, input.messages))
            is Fact.StatusObserved -> change(state, input.session) { if (it.lastStatus == input.status) it else it.copy(lastStatus = input.status,
                statusChangedAt = if (it.lastStatus == null) it.statusChangedAt.takeIf { at -> at > 0 } ?: it.createdAt else input.at) }
            is Fact.ArchiveReadinessObserved -> change(state, input.session) {
                if (it != input.expected || it.archived) it
                else if (!input.ready) it.copy(archiveReadySince = null)
                else if (it.archiveReadySince == null) it.copy(archiveReadySince = input.at)
                else if (input.at - checkNotNull(it.archiveReadySince) >= input.delay && it.organismId == null && state.runs[it.id] == null) it.copy(archived = true) else it
            }
            is Fact.NativeSessionBound -> {
                require(requireRun(state, input.ref).phase in setOf(Phase.RUNNING, Phase.STOPPING)) { "Запуск уже остановлен" }
                changeRun(state, input.ref) { it.copy(piSessionId = input.nativeSessionId, needsHistorySeed = false) }
            }
            is Fact.RunFinished -> finish(state, input)
            is Fact.RunOutputPublished -> {
                require(requireRun(state, input.ref).phase in setOf(Phase.RUNNING, Phase.STOPPING)) { "Запуск уже остановлен" }
                Transition(publishHistory(state, ref(requireRunSession(state, input.ref)), listOf(input.message)))
            }
            is Fact.RunStopped -> {
                val run = requireRun(state, input.ref)
                val changed = changeRun(state, input.ref) { it.copy(pendingRun = it.pendingRun?.copy(intent = ExecutionIntent.STOP)) }.state
                Transition(changed.copy(runs = changed.runs + (input.ref.session.id to run.copy(phase = if (input.unknown || "workspace:${input.ref.session.id}" in state.unknownChildren || run.phase in setOf(Phase.UNKNOWN, Phase.ABANDONING)) Phase.UNKNOWN else Phase.INTERRUPTED,
                    knownStopped = run.knownStopped || !input.unknown && run.phase in setOf(Phase.RUNNING, Phase.STOPPING)))))
            }
            is Fact.AbandonAcknowledged -> {
                val run = requireRun(state, input.ref)
                require(run.phase == Phase.ABANDONING && input.acknowledgement.predecessor == run.abandonAttempt &&
                    input.acknowledgement.parentDecisionId == run.abandonDecisionId && input.acknowledgement.id.isNotBlank()) { "Остановка предыдущей попытки не подтверждена" }
                val changed = changeRun(state, input.ref) { it.copy(pendingRun = null) }.state
                Transition(changed.copy(runs = changed.runs - input.ref.session.id,
                    acknowledgements = changed.acknowledgements + (input.ref.session.id to input.acknowledgement),
                    noDispatchAcknowledgements = changed.noDispatchAcknowledgements - input.ref.session.id))
            }
            is Fact.NoDispatchAcknowledged -> {
                val run = requireRun(state, input.ref)
                require(run.phase == Phase.ABANDONING && run.abandonAttempt == null &&
                    input.acknowledgement.proof == run.abandonNoDispatchProof && input.acknowledgement.id.isNotBlank() &&
                    input.acknowledgement.parentDecisionId == run.abandonDecisionId) { "Подтверждение относится к другому запросу" }
                val changed = changeRun(state, input.ref) { it.copy(pendingRun = null) }.state
                Transition(changed.copy(runs = changed.runs - input.ref.session.id,
                    acknowledgements = changed.acknowledgements - input.ref.session.id,
                    noDispatchAcknowledgements = changed.noDispatchAcknowledgements + (input.ref.session.id to input.acknowledgement)))
            }
            is Fact.RecoveryAcknowledgementConsumed -> {
                val session = requireRunSession(state, input.ref)
                val expected = state.acknowledgements[session.id]?.id ?: state.noDispatchAcknowledgements[session.id]?.id
                require(expected != null && input.proof.acknowledgementId == expected && input.proof.engine == session.engine &&
                    input.proof.sessionId == session.id && input.proof.requestId == input.ref.requestId) { "Подтверждение передачи относится к другому запуску" }
                Transition(state.copy(acknowledgements = state.acknowledgements - session.id,
                    noDispatchAcknowledgements = state.noDispatchAcknowledgements - session.id))
            }
            is Fact.TitleRequested -> {
                val session = requireSession(state, input.session)
                require(input.requestId.isNotBlank())
                if (!session.needsShortTitle()) Transition(state)
                else Transition(state.copy(titleRequests = state.titleRequests + (session.id to input.requestId)))
            }
            is Fact.PromptNamed -> change(state, input.session) { it.namedFromPrompt(input.prompt, input.localSummaryAllowed) }
            is Fact.TitleObserved -> change(state, input.session) {
                if (state.titleRequests[it.id] != input.requestId || !it.needsShortTitle()) it
                else it.copy(shortTitle = compactSessionTitle(input.title).orEmpty())
            }
            is Fact.PlanSessionBound -> change(state, input.session) {
                val planning = input.role == CodingSessionRole.ORCHESTRATOR
                val named = input.suggestedName?.takeIf { _ -> !it.nameManuallySet && (it.name.isDefaultSessionName() || it.name == input.legacyName) } ?: it.name
                val changed = if (planning && !it.planningMode) it.changeInteractionMode(CodingInteractionMode.PLANNING, state.runs[it.id] != null) else it
                changed.copy(role = input.role, planId = input.planId ?: changed.planId, stageId = input.stageId ?: changed.stageId,
                    stageNumber = input.stageNumber ?: changed.stageNumber, orchestratorNumber = input.orchestratorNumber ?: changed.orchestratorNumber,
                    continuationOfNumber = input.continuationOfNumber, name = named)
            }
            is Fact.OrganismProjected -> projectOrganism(state, input)
            is Fact.WorktreeProjected -> projectWorktree(state, input)
            is Fact.WorktreeUnavailable -> { requireRun(state, input.ref); changeRun(state, input.ref) { it.copy(pendingRun = it.pendingRun?.copy(worktreeEnabled = false)) } }
            Fact.Restored, Fact.PersistenceUnknown -> error("Handled before dispatch")
        } } catch (failure: IllegalArgumentException) { reject(failure.message ?: "Действие больше недоступно") }
    }

    private fun requireSession(state: State, ref: SessionRef): CodingSession =
        requireNotNull(state.session(ref)) { "Сессия удалена или её запуск изменился" }
    private fun requireRun(state: State, ref: RunRef): Run = requireNotNull(state.run(ref)) { "Запрос уже заменён" }
    private fun requireRunSession(state: State, ref: RunRef): CodingSession {
        val run = requireRun(state, ref)
        return requireNotNull(state.sessions[ref.session.id]?.takeIf { it.runtimeGeneration == run.executionGeneration }) { "Поколение исполнителя изменилось" }
    }
    private inline fun changeRun(state: State, ref: RunRef, change: (CodingSession) -> CodingSession): Transition {
        val session = requireRunSession(state, ref)
        return Transition(state.copy(sessions = state.sessions + (session.id to change(session))))
    }
    private inline fun change(state: State, ref: SessionRef, change: (CodingSession) -> CodingSession): Transition {
        val session = requireSession(state, ref)
        return Transition(state.copy(sessions = state.sessions + (session.id to change(session))))
    }
    private fun validateMessages(messages: List<CodingMessage>) {
        require(messages.all { it.id.isNotBlank() } && messages.distinctBy { it.id }.size == messages.size) { "Повторяющиеся сообщения" }
    }
    private fun createSession(state: State, input: Intent.CreateSession): Transition {
        val session = input.session
        require(session.projectId == state.project!!.id && session.id.isNotBlank() && session.id != state.project.id &&
            session.id !in state.sessions && session.id !in state.removedSessions) { "Идентификатор сессии уже занят" }
        require(session.engine != null && session.pendingRun == null && session.queuedPrompts.isEmpty() && (session.runtimeGeneration == 0L || session.planId != null && session.stageId != null) &&
            session.organismId == null && session.taskWorktree == null) { "Новая сессия не может наследовать работу" }
        session.forPendingRun(); validateMessages(input.messages)
        return Transition(state.copy(sessions = state.sessions + (session.id to session), histories = state.histories + (session.id to input.messages)),
            listOf(Effect.SessionCreated(session)))
    }
    private fun validateRequest(state: State, session: CodingSession, request: CodingRunCheckpoint, queued: Boolean) {
        val ownQueued = if (queued) session.queuedPrompts.firstOrNull { it.runId == request.runId } else null
        require(ownQueued == null || ownQueued.copy(responseId = request.responseId, responseTimelineId = request.responseTimelineId,
            interactionMode = request.interactionMode, workspaceTaskId = request.workspaceTaskId) == request) { "Сохранённый запрос изменился" }
        val other = state.sessions.values.flatMap { listOfNotNull(it.pendingRun) + it.queuedPrompts }.filterNot { queued && it == ownQueued }
        require(request.runId.isNotBlank() && request.messageId.isNotBlank() && request.runId !in state.startedRequests &&
            other.none { it.runId == request.runId || it.messageId == request.messageId }) { "Запрос уже принят" }
        val reserved = other.flatMap { listOf(it.messageId, it.responseId, it.responseTimelineId) }.filter { it.isNotBlank() }.toSet() +
            state.histories.values.flatten().flatMap { listOfNotNull(it.id, it.timelineId) } + state.removedMessages.values.flatten()
        val freshOutputIds = listOf(request.responseId, request.responseTimelineId).filter { it.isNotBlank() }
        require(freshOutputIds.distinct().size == freshOutputIds.size && freshOutputIds.none { it == request.messageId || it in reserved }) { "Идентификатор ответа уже занят" }
        val existing = state.histories[session.id].orEmpty().firstOrNull { it.id == request.messageId }
        require(existing == null || queued && ownQueued != null && existing.role == CodingRole.USER) { "Идентификатор сообщения уже занят" }
    }
    private fun begin(state: State, input: Intent.BeginRun): Transition {
        val session = requireSession(state, input.session)
        require(!session.archived && state.runs[session.id] == null && session.pendingRun == null) { "Предыдущий исход ещё не подтверждён" }
        require("workspace:${session.id}" !in state.unknownChildren) { "Исход операции с рабочей копией неизвестен" }
        val request = input.request
        require(request.responseId.isNotBlank() && request.responseTimelineId.isNotBlank() && request.intent == ExecutionIntent.RUN)
        validateRequest(state, session, request, queued = true)
        require(request.interactionMode == null || request.interactionMode == session.interactionMode) { "Режим сессии изменился" }
        val generation = (state.generations[session.id] ?: 0) + 1
        val ref = RunRef(input.session, request.runId, generation, request.messageId, request.responseId, request.responseTimelineId)
        val accepted = request.copy(interactionMode = session.interactionMode)
        val saved = session.namedFromPrompt(accepted.prompt, input.localSummaryAllowed).copy(pendingRun = accepted,
            queuedPrompts = session.queuedPrompts.filterNot { it.runId == accepted.runId }, manuallyVerifiedResponseId = null)
        val next = state.copy(sessions = state.sessions + (session.id to saved), runs = state.runs + (session.id to Run(ref, Phase.RUNNING)),
            generations = state.generations + (session.id to generation), startedRequests = state.startedRequests + accepted.runId)
        return Transition(next, listOf(Effect.RunRequest(ref, accepted, state.acknowledgements[session.id], state.noDispatchAcknowledgements[session.id])))
    }
    private fun publishHistory(state: State, ref: SessionRef, messages: List<CodingMessage>): State {
        val session = requireSession(state, ref); validateMessages(messages)
        val removed = state.removedMessages[session.id].orEmpty()
        val updates = messages.filterNot { it.id in removed }.associateBy { it.id }
        val old = state.histories[session.id].orEmpty()
        updates.forEach { (id, next) -> old.firstOrNull { it.id == id }?.let { previous ->
            require(previous.role == next.role && previous.origin == next.origin) { "Принадлежность сообщения изменилась" }
        } }
        val ids = old.map { it.id }.toSet()
        val merged = if (updates.keys.containsAll(ids)) updates.values.toList()
            else old.map { updates[it.id] ?: it } + updates.values.filterNot { it.id in ids }
        return state.copy(histories = state.histories + (session.id to merged))
    }
    private fun finish(state: State, input: Fact.RunFinished): Transition {
        val run = requireRun(state, input.ref)
        require(run.phase in setOf(Phase.RUNNING, Phase.STOPPING)) { "Запуск уже остановлен" }
        require(input.response.id == input.ref.responseId && input.response.role == CodingRole.AGENT) { "Ответ принадлежит другому запросу" }
        var next = publishHistory(state, ref(requireRunSession(state, input.ref)), listOf(input.response))
        val session = requireRunSession(next, input.ref)
        val completed = input.outcomeKnown && !input.response.failed && "workspace:${session.id}" !in state.unknownChildren
        next = next.copy(sessions = next.sessions + (session.id to session.copy(pendingRun =
            if (completed) null else session.pendingRun?.copy(intent = ExecutionIntent.STOP))),
            runs = if (completed) next.runs - session.id else next.runs + (session.id to run.copy(phase = if (input.outcomeKnown && "workspace:${session.id}" !in state.unknownChildren) Phase.INTERRUPTED else Phase.UNKNOWN, knownStopped = input.outcomeKnown)))
        return Transition(next)
    }
    private fun importLegacy(state: State, input: Fact.LegacyImported): Transition {
        require(input.project.id.isNotBlank() && input.sessions.distinctBy { it.id }.size == input.sessions.size)
        require(input.sessions.all { it.id.isNotBlank() && it.id != input.project.id && it.projectId == input.project.id && it.engine != null })
        require(input.histories.keys.all { id -> input.sessions.any { it.id == id } })
        input.histories.values.forEach(::validateMessages)
        val sessions = input.sessions.associateBy { it.id }
        val runs = sessions.mapNotNull { (id, session) -> session.pendingRun?.let { request ->
            id to Run(RunRef(ref(session), request.runId, 1, request.messageId, request.responseId, request.responseTimelineId), Phase.UNKNOWN)
        } }.toMap()
        return Transition(state.copy(project = input.project, sessions = sessions, histories = input.histories,
            removedMessages = input.removedMessages, runs = runs, generations = runs.mapValues { 1L },
            startedRequests = runs.values.map { it.ref.requestId }.toSet(), orchestrations = input.orchestrations.associateBy { it.sessionId }))
    }
    private fun acceptRevision(state: State, key: String, revision: ChildRevision): Boolean {
        require(revision.stream.isNotBlank() && revision.seq >= 0 && revision.resetEpoch >= 0 && revision.inputId.isNotBlank()) { "Неверное подтверждение дочернего журнала" }
        val previous = state.childRevisions[key] ?: return true
        require(revision.stream == previous.stream && revision.resetEpoch == previous.resetEpoch) { "Дочерний журнал был заменён" }
        require(revision.seq != previous.seq || revision == previous) { "Подтверждение дочернего журнала изменилось" }
        return revision.seq > previous.seq
    }
    private fun projectWorktree(state: State, input: Fact.WorktreeProjected): Transition {
        val session = requireSession(state, input.session)
        val key = "workspace:${session.id}"
        val newer = acceptRevision(state, key, input.revision)
        if (input.revision.seq < (state.childRevisions[key]?.seq ?: 0)) return Transition(state)
        val old = session.taskWorktree
        // An uncertain child write may have no new durable revision or readable task bytes.
        if (!newer && !input.unknown) {
            require(input.task == old) { "Проекция дочернего журнала изменилась без новой записи" }
            return Transition(state)
        }
        val task = if (input.unknown && input.task == null) old else input.task
        require(old == null || task?.taskId == old.taskId || old.phase == TaskWorktreePhase.COMPLETE &&
            task?.phase in setOf(TaskWorktreePhase.PREPARING, TaskWorktreePhase.RUNNING)) { "Рабочая задача уже заменена" }
        val next = change(state, input.session) { it.copy(taskWorktree = task) }.state
        val run = next.runs[session.id]
        val stopped = if (!input.unknown && newer && run?.phase == Phase.UNKNOWN && run.knownStopped)
            next.runs + (session.id to run.copy(phase = Phase.INTERRUPTED)) else next.runs
        return Transition(next.copy(childRevisions = next.childRevisions + (key to input.revision), runs = stopped,
            unknownChildren = if (input.unknown) next.unknownChildren + key else next.unknownChildren - key))
    }
    private fun projectOrganism(state: State, input: Fact.OrganismProjected): Transition {
        val organism = input.organism
        require(organism.projectId == state.project!!.id) { "Организм принадлежит другому проекту" }
        val key = "organism:${organism.id}"
        if (!acceptRevision(state, key, input.revision)) return Transition(state)
        val root = requireNotNull(state.sessions[organism.zygoteId]) { "Корневая сессия удалена" }
        var next = state
        organism.sessions.values.filterNot { it.id in organism.historyDeletedIds }.forEach { node ->
            require(node.id !in next.removedSessions) { "Сессия удалена" }
            val old = next.sessions[node.id]
            require(old?.organismId == null || old.organismId == organism.id && old.parentSessionId == node.originParentId) { "Происхождение сессии изменилось" }
            require(old == null || node.generation >= old.runtimeGeneration) { "Запуск сессии уже заменён" }
            val generationChanged = old != null && node.generation != old.runtimeGeneration
            val base = old ?: CodingSession(node.id, organism.projectId, node.name, organism.createdAt,
                engine = root.engine, modelSelection = root.modelSelection)
            val name = if (!node.nameManuallySet && !base.nameManuallySet && !base.name.isDefaultSessionName()) base.name else node.name
            val projected = base.copy(name = name, nameManuallySet = node.nameManuallySet || base.nameManuallySet,
                parentSessionId = node.originParentId, organismId = organism.id, runtimeGeneration = node.generation,
                sessionKind = node.kind, observedState = node.observed, desiredState = node.desired, archived = node.archived,
                planningRulesSnapshot = node.rules, planningMode = node.mode == CodingInteractionMode.PLANNING,
                researchMode = node.mode == CodingInteractionMode.RESEARCH,
                role = if (node.mode == CodingInteractionMode.PLANNING) CodingSessionRole.ORCHESTRATOR else if (base.role == CodingSessionRole.WORKER) base.role else CodingSessionRole.CHAT,
                piSessionId = if (generationChanged && node.previousGeneration != null) "" else base.piSessionId,
                needsHistorySeed = base.needsHistorySeed || generationChanged && node.previousGeneration != null)
            val oldRun = next.runs[node.id]
            val adopted = if (generationChanged && oldRun != null) oldRun.copy(executionGeneration = node.generation,
                phase = if (node.previousGeneration != null) Phase.UNKNOWN else oldRun.phase) else oldRun
            next = next.copy(sessions = next.sessions + (node.id to projected),
                runs = if (adopted != null) next.runs + (node.id to adopted) else next.runs)
        }
        return Transition(next.copy(childRevisions = next.childRevisions + (key to input.revision)))
    }
}
