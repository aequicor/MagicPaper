package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.machine.Machine
import io.aequicor.magicpaper.machine.MachineId
import io.aequicor.magicpaper.machine.Step
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One notebook owns its questions, ordered requests and history. UI/configuration/drafts are projections or child owners. */
object ChatMachine : Machine<ChatMachine.State, ChatMachine.Input, ChatMachine.Effect> {
    override val id = MachineId("chat")
    override val space get() = ChatSpace
    /** Bridge to the owner's own reducer: [Transition] and [reduce] keep every call site. */
    override fun step(state: State, input: Input) = reduce(state, input).let { Step(it.state, it.effects) }

    @Serializable enum class Phase { RUNNING, STOPPING, INTERRUPTED, UNKNOWN, RECOVERING }
    @Serializable enum class Failure { MODEL, RESEARCH, PERSISTENCE, MISSING_BACKEND, UNKNOWN_OUTCOME }
    @Serializable data class RunRef(val sessionId: String, val runId: String, val generation: Long,
        val messageId: String, val responseId: String, val timelineId: String)
    @Serializable data class OutputProof(val runId: String, val attemptId: String, val identity: String, val digest: String)
    @Serializable data class CheckedSource(val url: String, val title: String, val problem: String? = null)
    @Serializable data class ResponseContext(val sourceTask: Boolean, val checked: List<CheckedSource>,
        val sources: List<SearchHit>, val attachments: List<Attachment> = emptyList())
    @ConsistentCopyVisibility
    data class Run internal constructor(val ref: RunRef, val phase: Phase, val failure: Failure? = null,
        /** Only a live, explicit clarification may continue after a proved stop. Restore clears this authority. */
        val clarification: CodingRunCheckpoint? = null, val responseContext: ResponseContext? = null)

    @ConsistentCopyVisibility
    data class State internal constructor(
        val notebookId: String = "",
        val initialized: Boolean = false,
        val sessions: Map<String, ChatSession> = emptyMap(),
        val runs: Map<String, Run> = emptyMap(),
        val generations: Map<String, Long> = emptyMap(),
        val startedRuns: Set<String> = emptySet(),
        val removedIds: Set<String> = emptySet(),
        val deleted: Boolean = false,
        val persistenceUnknown: Boolean = false,
    ) {
        val notebook: ChatSession? get() = sessions[notebookId]
        fun busy(id: String): Boolean = !persistenceUnknown && runs[id]?.phase in setOf(Phase.RUNNING, Phase.STOPPING, Phase.RECOVERING)
    }

    @Serializable sealed interface Input
    @Serializable sealed interface Intent : Input {
        @Serializable @SerialName("CreateNotebook") data class CreateNotebook(val id: String, val at: Long,
            val model: ModelSelection? = null) : Intent
        @Serializable @SerialName("ImportNotebook") data class ImportNotebook(val sessions: List<ChatSession>) : Intent
        @Serializable @SerialName("ForkNotebook") data class ForkNotebook(val source: ChatSession, val notebook: ChatSession,
            val id: String, val at: Long, val messageIds: Map<String, String>, val throughMessageId: String? = null) : Intent
        @Serializable @SerialName("CreateQuestion") data class CreateQuestion(val id: String, val sourceId: String, val at: Long) : Intent
        @Serializable @SerialName("SelectQuestion") data class SelectQuestion(val id: String) : Intent
        @Serializable @SerialName("Submit") data class Submit(val sessionId: String, val request: CodingRunCheckpoint, val at: Long,
            val responseBindingId: String? = null) : Intent
        @Serializable @SerialName("FollowUp") data class FollowUp(val sessionId: String, val answerId: String,
            val request: CodingRunCheckpoint, val at: Long) : Intent
        /** Only an explicit fresh action or a live proved completion dispatches this; replay never does. */
        @Serializable @SerialName("AdvanceQueue") data class AdvanceQueue(val sessionId: String, val at: Long) : Intent
        @Serializable @SerialName("Pause") data class Pause(val ref: RunRef) : Intent
        @Serializable @SerialName("Clarify") data class Clarify(val ref: RunRef, val request: CodingRunCheckpoint, val at: Long) : Intent
        @Serializable @SerialName("Recover") data class Recover(val ref: RunRef) : Intent
        @Serializable @SerialName("Discard") data class Discard(val ref: RunRef, val at: Long) : Intent
        @Serializable @SerialName("EditMessage") data class EditMessage(val sessionId: String, val messageId: String,
            val text: String, val request: CodingRunCheckpoint, val at: Long) : Intent
        @Serializable @SerialName("DeleteMessage") data class DeleteMessage(val sessionId: String, val messageId: String, val at: Long) : Intent
        @Serializable @SerialName("SetModel") data class SetModel(val sessionId: String, val selection: ModelSelection) : Intent
        @Serializable @SerialName("UnlinkProfile") data class UnlinkProfile(val profileId: String) : Intent
        @Serializable @SerialName("SetMediaTool") data class SetMediaTool(val kind: MediaKind, val enabled: Boolean) : Intent
        @Serializable @SerialName("AddResources") data class AddResources(val questionId: String,
            val shared: Boolean, val resources: List<ResearchResource>) : Intent
        @Serializable @SerialName("RemoveResource") data class RemoveResource(val questionId: String,
            val shared: Boolean, val resourceId: String) : Intent
        @Serializable @SerialName("ShareResource") data class ShareResource(val questionId: String, val resourceId: String) : Intent
        @Serializable @SerialName("SelectResources") data class SelectResources(val questionId: String,
            val keys: Set<String>, val enabled: Boolean) : Intent
        @Serializable @SerialName("Archive") data class Archive(val sessionId: String, val archived: Boolean,
            val at: Long, val automatic: Boolean = false) : Intent
        @Serializable @SerialName("Delete") data class Delete(val sessionId: String) : Intent
    }
    @Serializable sealed interface Fact : Input {
        /** A one-time import; the original legacy files remain recoverable until this is durable. */
        @Serializable @SerialName("LegacyImported") data class LegacyImported(val notebookId: String, val sessions: List<ChatSession>) : Fact
        @Serializable @SerialName("LegacyHydrated") data class LegacyHydrated(val sessionId: String,
            val resources: List<ResearchResource>, val sources: List<ResearchResource>, val model: ModelSelection?) : Fact
        @Serializable @SerialName("ExtensionBound") data class ExtensionBound(val ref: RunRef, val bindingId: String) : Fact
        @Serializable @SerialName("Progress") data class Progress(val ref: RunRef, val activity: List<CodingStep>,
            val content: List<TranscriptBlock>) : Fact
        @Serializable @SerialName("ResponseContextPrepared") data class ResponseContextPrepared(val ref: RunRef, val context: ResponseContext) : Fact
        @Serializable @SerialName("NativeSessionBound") data class NativeSessionBound(val ref: RunRef, val nativeSessionId: String) : Fact
        @Serializable @SerialName("SourcesDiscovered") data class SourcesDiscovered(val ref: RunRef,
            val resources: List<ResearchResource>, val shared: Boolean) : Fact
        @Serializable @SerialName("ReplyStored") data class ReplyStored(val ref: RunRef, val message: ChatMessage) : Fact
        @Serializable @SerialName("RunStopped") data class RunStopped(val ref: RunRef, val unknown: Boolean,
            val failure: Failure? = null, val at: Long) : Fact
        @Serializable @SerialName("RecoveredReply") data class RecoveredReply(val ref: RunRef,
            val proof: OutputProof, val message: ChatMessage) : Fact
        @Serializable @SerialName("RecoveryUnavailable") data class RecoveryUnavailable(val ref: RunRef,
            val unknown: Boolean, val missing: Boolean = false) : Fact
        @Serializable @SerialName("Restored") data object Restored : Fact
        @Serializable @SerialName("PersistenceUnknown") data object PersistenceUnknown : Fact
    }
    sealed interface Effect {
        data class RunRequest(val ref: RunRef) : Effect
        data class AbortRequest(val ref: RunRef) : Effect
        data class InspectSavedOutput(val ref: RunRef) : Effect
        data class RequestAccepted(val sessionId: String, val runId: String) : Effect
        /** A live runner may advance the next accepted request after this output; replay discards the output. */
        data class RequestCompleted(val sessionId: String) : Effect
        data class CleanupDeleted(val sessionIds: Set<String>) : Effect
        data class Reject(val reason: String) : Effect
    }
    data class Transition(val state: State, val effects: List<Effect> = emptyList())

    fun initial() = State()

    fun reduce(state: State, input: Input): Transition {
        fun reject(reason: String) = Transition(state, listOf(Effect.Reject(reason)))
        if (input == Fact.PersistenceUnknown) return Transition(state.copy(persistenceUnknown = true))
        if (state.persistenceUnknown) return reject("Не удалось подтвердить сохранение. Восстановите чат перед новым действием.")
        if (input == Fact.Restored) return Transition(state.copy(runs = state.runs.mapValues { (_, run) ->
            run.copy(phase = if (run.phase in setOf(Phase.RUNNING, Phase.STOPPING, Phase.RECOVERING)) Phase.UNKNOWN else run.phase,
                clarification = null)
        }))
        if (state.deleted) return reject("Чат удалён")
        if (!state.initialized && input !is Fact.LegacyImported && input !is Intent.CreateNotebook && input !is Intent.ImportNotebook && input !is Intent.ForkNotebook)
            return reject("Чат ещё не восстановлен")
        return when (input) {
            is Intent.CreateNotebook -> when {
                state.initialized || input.id.isBlank() -> reject("Чат уже существует или не указан его идентификатор")
                else -> Transition(State(notebookId = input.id, initialized = true, sessions = mapOf(input.id to
                    ChatSession(input.id, "Новое исследование", input.at, input.at, modelSelection = input.model,
                        researchResourcesInitialized = true))))
            }
            is Fact.LegacyImported -> when {
                state.initialized -> reject("Сохранённая история уже импортирована")
                !validNotebook(input.notebookId, input.sessions) -> reject("Сохранённая история повреждена")
                else -> Transition(imported(input.notebookId, input.sessions))
            }
            is Fact.LegacyHydrated -> {
                val session = state.sessions[input.sessionId] ?: return reject("Вопрос не найден")
                Transition(state.withSession(session.copy(
                    resources = if (!session.researchResourcesInitialized) input.resources else session.resources,
                    questionResources = if (!session.researchResourcesInitialized) input.sources else session.questionResources,
                    researchResourcesInitialized = true, modelSelection = session.modelSelection ?: input.model)))
            }
            is Fact.ExtensionBound -> {
                val run = state.match(input.ref) ?: return reject("Запрос изменился")
                val session = state.sessions.getValue(run.ref.sessionId)
                if (run.phase != Phase.RUNNING || input.bindingId.isBlank()) reject("Расширение не доступно для этого запроса")
                else Transition(state.withSession(session.copy(layoutProjectId = session.layoutProjectId ?: input.bindingId)))
            }
            is Intent.ImportNotebook -> {
                val root = input.sessions.singleOrNull { it.researchParentId == null }
                when {
                    root == null || !validNotebook(root.id, input.sessions) -> reject("Некорректный архив чата")
                    state.initialized && root.id != state.notebookId -> reject("Архив принадлежит другому чату")
                    state.runs.isNotEmpty() -> reject("Завершите текущие запросы перед импортом")
                    input.sessions.any { it.id in state.removedIds } -> reject("Импорт не может восстановить удалённый вопрос с прежним идентификатором")
                    else -> {
                        val restored = imported(root.id, input.sessions, state.generations)
                        Transition(restored.copy(startedRuns = state.startedRuns + restored.startedRuns,
                            removedIds = state.removedIds + (state.sessions.keys - restored.sessions.keys)))
                    }
                }
            }
            is Intent.ForkNotebook -> {
                val source = input.source
                val root = input.notebook
                val through = input.throughMessageId?.let { id -> source.messages.indexOfFirst { it.id == id } } ?: source.messages.lastIndex
                val messages = source.messages.take(through + 1)
                if (state.initialized || input.id.isBlank() || source.researchChatId != root.id || root.researchParentId != null ||
                    input.throughMessageId != null && through < 0 || messages.any { input.messageIds[it.id].isNullOrBlank() } ||
                    input.messageIds.values.distinct().size != input.messageIds.size) return reject("Не удалось создать копию истории")
                val fork = source.copy(id = input.id, title = "${source.title} — форк", createdAt = input.at, updatedAt = input.at,
                    messages = messages.map { it.forChatFork(input.messageIds.getValue(it.id)) }, nativeSessionId = "",
                    pendingRun = null, pendingActivity = emptyList(), pendingContent = emptyList(), queuedPrompts = emptyList(),
                    acquireComputerAccess = false, archived = false, archiveRestoredAt = null, researchParentId = null, selectedQuestionId = null,
                    resources = root.resources, excludedResourceUrls = root.excludedResourceUrls, researchResourcesInitialized = true, mediaTools = root.mediaTools)
                Transition(State(fork.id, initialized = true, sessions = mapOf(fork.id to fork)))
            }
            is Intent.CreateQuestion -> {
                val root = state.notebook ?: return reject("Чат не найден")
                val source = state.sessions[input.sourceId] ?: return reject("Вопрос не найден")
                if (input.id.isBlank() || input.id in state.sessions || input.id in state.removedIds) reject("Вопрос уже существует")
                else {
                    val question = ChatSession(input.id, "Новый вопрос", input.at, input.at, researchParentId = root.id,
                        modelSelection = source.modelSelection ?: root.modelSelection, llmProfileId = root.llmProfileId)
                    Transition(state.copy(sessions = state.sessions + (question.id to question) + (root.id to root.copy(selectedQuestionId = question.id))))
                }
            }
            is Intent.SelectQuestion -> {
                val root = state.notebook ?: return reject("Чат не найден")
                if (input.id !in state.sessions) reject("Вопрос не найден")
                else Transition(state.withSession(root.copy(selectedQuestionId = input.id)))
            }
            is Intent.FollowUp -> {
                val session = state.sessions[input.sessionId] ?: return reject("Вопрос не найден")
                val answer = session.messages.lastOrNull()
                if (!historyMutable(state, session) || answer?.id != input.answerId || answer.role != ChatRole.AGENT ||
                    input.request.prompt !in answer.followUps) reject("Продолжение относится к прежнему ответу")
                else reduce(state, Intent.Submit(input.sessionId, input.request, input.at))
            }
            is Intent.Submit -> {
                val original = state.sessions[input.sessionId] ?: return reject("Вопрос не найден")
                val session = original.copy(layoutProjectId = original.layoutProjectId ?: input.responseBindingId)
                if (!validNewRequest(state, session, input.request)) return reject("Некорректный или повторный запрос")
                if (session.pendingRun != null || input.sessionId in state.runs) {
                    val queued = state.withSession(session.copy(queuedPrompts = session.queuedPrompts + input.request))
                    Transition(attachFiles(queued, session.id, input.request), listOf(Effect.RequestAccepted(session.id, input.request.runId)))
                }
                else begin(state, session, input.request, input.at)
            }
            is Intent.AdvanceQueue -> {
                val session = state.sessions[input.sessionId] ?: return reject("Вопрос не найден")
                if (session.pendingRun != null || input.sessionId in state.runs) return reject("Предыдущий запрос не завершён")
                val request = session.queuedPrompts.firstOrNull() ?: return reject("Очередь пуста")
                if (runKey(session.id, request.runId) in state.startedRuns) reject("Этот запрос уже запускался")
                else begin(state, session, request, input.at)
            }
            is Intent.Pause -> {
                val run = state.match(input.ref) ?: return reject("Запрос изменился")
                if (run.phase != Phase.RUNNING) reject("Запрос сейчас не выполняется")
                else Transition(state.withRun(run.copy(phase = Phase.STOPPING)).stopCheckpoint(input.ref.sessionId, true),
                    listOf(Effect.AbortRequest(input.ref)))
            }
            is Intent.Clarify -> {
                val run = state.match(input.ref) ?: return reject("Запрос изменился")
                val session = state.sessions.getValue(input.ref.sessionId)
                if (!validNewRequest(state, session, input.request, existingMessage = true)) return reject("Некорректное уточнение")
                if (run.phase !in setOf(Phase.RUNNING, Phase.INTERRUPTED, Phase.UNKNOWN)) return reject("Дождитесь остановки запроса")
                val queued = attachFiles(state.withSession(session.copy(queuedPrompts = listOf(input.request) + session.queuedPrompts)), session.id, input.request)
                if (run.phase == Phase.RUNNING) Transition(queued.withRun(run.copy(phase = Phase.STOPPING, clarification = input.request))
                    .stopCheckpoint(session.id, false), listOf(Effect.RequestAccepted(session.id, input.request.runId), Effect.AbortRequest(run.ref)))
                else if (run.phase == Phase.INTERRUPTED) begin(queued, queued.sessions.getValue(session.id), input.request, input.at, keepProgress = true)
                else Transition(queued, listOf(Effect.RequestAccepted(session.id, input.request.runId)))
            }
            is Intent.Recover -> {
                val run = state.match(input.ref) ?: return reject("Запрос изменился")
                if (run.phase !in setOf(Phase.UNKNOWN, Phase.INTERRUPTED)) reject("Дождитесь завершения текущей операции")
                else Transition(state.withRun(run.copy(phase = Phase.RECOVERING, clarification = null)), listOf(Effect.InspectSavedOutput(run.ref)))
            }
            is Intent.Discard -> {
                val run = state.match(input.ref) ?: return reject("Запрос изменился")
                if (run.phase !in setOf(Phase.UNKNOWN, Phase.INTERRUPTED)) return reject("Сначала остановите запрос")
                val session = state.sessions.getValue(input.ref.sessionId)
                val partial = session.pendingContent.filterIsInstance<TranscriptBlock.Markdown>().joinToString("\n\n") { it.text }
                val message = ChatMessage(input.ref.responseId.ifBlank { "${input.ref.messageId}-left-stopped" }, ChatRole.AGENT,
                    partial.ifBlank { "Запрос оставлен без продолжения." }, input.at,
                    researchActivity = session.pendingActivity, content = session.pendingContent)
                // Deliberately no RequestCompleted output: discard does not execute queued work.
                Transition(finish(state, run.ref, message))
            }
            is Intent.EditMessage -> {
                val session = state.sessions[input.sessionId] ?: return reject("Вопрос не найден")
                if (!historyMutable(state, session)) return reject("Сначала завершите текущий запрос")
                val index = session.messages.indexOfFirst { it.id == input.messageId }
                val message = session.messages.getOrNull(index) ?: return reject("Сообщение не найдено")
                if (message.role != ChatRole.USER || input.text.isBlank() && message.attachments.isEmpty() ||
                    input.request.messageId != message.id || input.request.prompt != input.text.trim() ||
                    input.request.attachments != message.attachments || !validNewRequest(state, session, input.request, true))
                    return reject("Некорректное изменение сообщения")
                val edited = session.copy(messages = session.messages.take(index) + message.copy(text = input.text.trim()), nativeSessionId = "",
                    pendingRun = null, pendingActivity = emptyList(), pendingContent = emptyList(), updatedAt = input.at)
                begin(state.withSession(edited), edited, input.request, input.at)
            }
            is Intent.DeleteMessage -> {
                val session = state.sessions[input.sessionId] ?: return reject("Вопрос не найден")
                when {
                    !historyMutable(state, session) -> reject("Сначала завершите текущий запрос")
                    session.messages.none { it.id == input.messageId } -> reject("Сообщение не найдено")
                    else -> Transition(state.withSession(session.copy(messages = session.messages.filterNot { it.id == input.messageId },
                        nativeSessionId = "", pendingRun = null, pendingActivity = emptyList(), pendingContent = emptyList(), updatedAt = input.at)))
                }
            }
            is Intent.SetModel -> {
                val session = state.sessions[input.sessionId] ?: return reject("Вопрос не найден")
                if (input.selection.profileId.isBlank() || input.selection.modelId.isBlank()) reject("Модель не выбрана")
                else Transition(state.withSession(session.copy(modelSelection = input.selection, llmProfileId = input.selection.profileId)))
            }
            is Intent.UnlinkProfile -> Transition(state.copy(sessions = state.sessions.mapValues { (_, session) ->
                session.copy(llmProfileId = session.llmProfileId?.takeUnless { it == input.profileId },
                    modelSelection = session.modelSelection?.takeUnless { it.profileId == input.profileId })
            }))
            is Intent.SetMediaTool -> {
                val root = state.notebook ?: return reject("Чат не найден")
                Transition(state.withSession(root.copy(mediaTools = root.mediaTools.withEnabled(input.kind, input.enabled))))
            }
            is Intent.AddResources -> addResources(state, input.questionId, input.shared, input.resources, explicit = true)
            is Intent.RemoveResource -> {
                val question = state.sessions[input.questionId] ?: return reject("Вопрос не найден")
                val target = if (input.shared) state.notebook!! else question
                val resources = if (input.shared) target.resources else target.questionResources
                val removed = resources.firstOrNull { it.id == input.resourceId } ?: return reject("Источник не найден")
                val remaining = resources.filterNot { it.id == removed.id }
                val urls = listOfNotNull(removed.url.takeIf(String::isNotBlank)).toSet()
                Transition(state.withSession(if (input.shared) target.copy(resources = remaining, excludedResourceUrls = target.excludedResourceUrls + urls)
                    else target.copy(questionResources = remaining, excludedQuestionResourceUrls = target.excludedQuestionResourceUrls + urls)))
            }
            is Intent.ShareResource -> {
                val question = state.sessions[input.questionId] ?: return reject("Вопрос не найден")
                val source = question.questionResources.firstOrNull { it.id == input.resourceId } ?: return reject("Источник не найден")
                val promoted = addResources(state, question.id, true, listOf(source), explicit = true)
                Transition(promoted.state.withSession(promoted.state.sessions.getValue(question.id).copy(disabledResourceKeys = question.disabledResourceKeys)))
            }
            is Intent.SelectResources -> {
                val question = state.sessions[input.questionId] ?: return reject("Вопрос не найден")
                val available = (state.notebook!!.resources + question.questionResources).mapTo(mutableSetOf()) { it.key }
                if (!available.containsAll(input.keys)) reject("Источник не найден") else Transition(state.withSession(question.copy(
                    disabledResourceKeys = if (input.enabled) question.disabledResourceKeys - input.keys else question.disabledResourceKeys + input.keys)))
            }
            is Intent.Archive -> {
                val session = state.sessions[input.sessionId] ?: return reject("Вопрос не найден")
                when {
                    input.archived && !historyMutable(state, session) -> reject("Дождитесь завершения запросов перед архивацией")
                    input.automatic && (!input.archived || input.at - maxOf(session.updatedAt, session.archiveRestoredAt ?: session.updatedAt) < ARCHIVE_DELAY) -> reject("Чат пока не готов к архивации")
                    else -> Transition(state.withSession(session.copy(archived = input.archived,
                        archiveRestoredAt = if (input.archived) session.archiveRestoredAt else input.at)))
                }
            }
            is Intent.Delete -> {
                if (input.sessionId !in state.sessions) return reject("Вопрос не найден")
                val ids = if (input.sessionId == state.notebookId) state.sessions.keys else setOf(input.sessionId)
                val effects = state.runs.values.filter { it.ref.sessionId in ids && it.phase in setOf(Phase.RUNNING, Phase.STOPPING) }
                    .map { Effect.AbortRequest(it.ref) } + Effect.CleanupDeleted(ids)
                val sessions = state.sessions - ids
                val root = sessions[state.notebookId]
                Transition(state.copy(sessions = if (root?.selectedQuestionId in ids) sessions + (root!!.id to root.copy(selectedQuestionId = null)) else sessions,
                    runs = state.runs - ids, removedIds = state.removedIds + ids, deleted = input.sessionId == state.notebookId), effects)
            }
            is Fact.Progress -> {
                val run = state.match(input.ref) ?: return reject("Запрос изменился")
                if (run.phase !in setOf(Phase.RUNNING, Phase.STOPPING)) reject("Запрос уже остановлен") else {
                    val session = state.sessions.getValue(run.ref.sessionId)
                    Transition(state.withSession(session.copy(pendingActivity = input.activity, pendingContent = input.content)))
                }
            }
            is Fact.ResponseContextPrepared -> {
                val run = state.match(input.ref) ?: return reject("Запрос изменился")
                if (run.phase !in setOf(Phase.RUNNING, Phase.STOPPING)) reject("Запрос уже остановлен")
                else Transition(state.withRun(run.copy(responseContext = input.context)))
            }
            is Fact.NativeSessionBound -> {
                val run = state.match(input.ref) ?: return reject("Запрос изменился")
                if (run.phase != Phase.RUNNING || input.nativeSessionId.isBlank()) reject("Запрос уже остановлен")
                else Transition(state.withSession(state.sessions.getValue(run.ref.sessionId).copy(nativeSessionId = input.nativeSessionId)))
            }
            is Fact.SourcesDiscovered -> {
                val run = state.match(input.ref) ?: return reject("Запрос изменился")
                if (run.phase != Phase.RUNNING) reject("Запрос уже остановлен")
                else addResources(state, run.ref.sessionId, input.shared, input.resources, explicit = false)
            }
            is Fact.ReplyStored -> {
                val run = state.match(input.ref) ?: return reject("Ответ принадлежит другому запросу")
                if (run.phase !in setOf(Phase.RUNNING, Phase.STOPPING) || !validReply(run.ref, input.message)) reject("Некорректный итоговый ответ")
                else Transition(finish(state, input.ref, input.message), if (state.sessions.getValue(input.ref.sessionId).pendingRun?.stoppedByUser == true)
                    emptyList() else listOf(Effect.RequestCompleted(input.ref.sessionId)))
            }
            is Fact.RunStopped -> {
                val run = state.match(input.ref) ?: return reject("Запрос изменился")
                if (run.phase !in setOf(Phase.RUNNING, Phase.STOPPING)) return reject("Запрос уже остановлен")
                val stopped = state.withRun(run.copy(phase = if (input.unknown) Phase.UNKNOWN else Phase.INTERRUPTED,
                    failure = if (input.unknown) Failure.UNKNOWN_OUTCOME else input.failure, clarification = null))
                    .stopCheckpoint(run.ref.sessionId, false)
                if (!input.unknown && run.clarification != null) {
                    val session = stopped.sessions.getValue(run.ref.sessionId)
                    begin(stopped, session, run.clarification, input.at, keepProgress = true)
                } else Transition(stopped)
            }
            is Fact.RecoveredReply -> {
                val run = state.match(input.ref) ?: return reject("Сохранённый ответ принадлежит другому запросу")
                if (run.phase != Phase.RECOVERING || input.proof.runId != input.ref.runId ||
                    input.proof.attemptId.isBlank() || input.proof.identity.isBlank() || input.proof.digest.isBlank() ||
                    !validReply(run.ref, input.message)) reject("Сохранённый ответ не подтверждён")
                else Transition(finish(state, input.ref, input.message)) // Inspection never advances old queues.
            }
            is Fact.RecoveryUnavailable -> {
                val run = state.match(input.ref) ?: return reject("Запрос изменился")
                if (run.phase != Phase.RECOVERING) reject("Проверка результата сейчас не выполняется")
                else Transition(state.withRun(run.copy(phase = if (input.unknown) Phase.UNKNOWN else Phase.INTERRUPTED,
                    failure = if (input.missing) Failure.MISSING_BACKEND else if (input.unknown) Failure.UNKNOWN_OUTCOME else null)))
            }
            Fact.Restored, Fact.PersistenceUnknown -> error("Handled above")
        }
    }

    private fun begin(state: State, session: ChatSession, request: CodingRunCheckpoint, at: Long, keepProgress: Boolean = false): Transition {
        val generation = (state.generations[session.id] ?: 0) + 1
        val ref = RunRef(session.id, request.runId, generation, request.messageId, request.responseId, request.responseTimelineId)
        val message = ChatMessage(request.messageId, ChatRole.USER, request.prompt, at, attachments = request.attachments)
        val updated = session.copy(pendingRun = request.copy(intent = ExecutionIntent.RUN, stoppedByUser = false),
            messages = if (session.messages.any { it.id == message.id }) session.messages.map {
                if (it.id == message.id) it.copy(text = request.prompt, attachments = request.attachments) else it
            } else session.messages + message,
            queuedPrompts = session.queuedPrompts.filterNot { it.runId == request.runId }, archived = false, updatedAt = at,
            title = if (session.messages.isEmpty()) request.prompt.take(40) else session.title,
            pendingActivity = if (keepProgress) session.pendingActivity else emptyList(),
            pendingContent = if (keepProgress) session.pendingContent else emptyList())
        var next = state.withSession(updated).withRun(Run(ref, Phase.RUNNING)).copy(
            generations = state.generations + (session.id to generation), startedRuns = state.startedRuns + runKey(session.id, request.runId))
        if (session.id != state.notebookId) next = next.withSession(next.notebook!!.copy(updatedAt = at))
        return Transition(attachFiles(next, session.id, request), listOf(Effect.RequestAccepted(session.id, request.runId), Effect.RunRequest(ref)))
    }

    private fun finish(state: State, ref: RunRef, message: ChatMessage): State {
        val session = state.sessions.getValue(ref.sessionId)
        return state.withSession(session.copy(messages = session.messages.filterNot { it.id == message.id } + message,
            updatedAt = message.createdAt, pendingRun = null, pendingActivity = emptyList(), pendingContent = emptyList()))
            .copy(runs = state.runs - ref.sessionId)
    }

    private fun addResources(state: State, questionId: String, shared: Boolean, resources: List<ResearchResource>, explicit: Boolean): Transition {
        val question = state.sessions[questionId] ?: return Transition(state, listOf(Effect.Reject("Вопрос не найден")))
        val root = state.notebook!!
        val excluded = root.excludedResourceUrls + question.excludedQuestionResourceUrls
        val additions = resources.filter { explicit || it.url !in excluded }.map { it.copy(readableText = null) }
        if (additions.any { it.id.isBlank() || it.url.isNotEmpty() && researchUrl(it.url) != it.url })
            return Transition(state, listOf(Effect.Reject("Некорректный источник")))
        val target = if (shared) root else question
        val keys = additions.map { it.key }.toSet()
        val urls = additions.map { it.url }.toSet()
        var next = state.withSession(if (shared) target.copy(resources = (target.resources + additions).distinctBy { it.key },
            questionResources = if (explicit) target.questionResources.filterNot { it.key in keys } else target.questionResources,
            excludedResourceUrls = if (explicit) target.excludedResourceUrls - urls else target.excludedResourceUrls)
        else target.copy(questionResources = (target.questionResources + additions.filterNot { source -> root.resources.any { it.key == source.key } }).distinctBy { it.key },
            excludedQuestionResourceUrls = if (explicit) target.excludedQuestionResourceUrls - urls else target.excludedQuestionResourceUrls))
        if (explicit) {
            val latest = next.sessions.getValue(questionId)
            next = next.withSession(latest.copy(disabledResourceKeys = latest.disabledResourceKeys - keys,
                questionResources = if (shared) latest.questionResources.filterNot { it.key in keys } else latest.questionResources))
        }
        return Transition(next)
    }

    private fun attachFiles(state: State, id: String, request: CodingRunCheckpoint): State = addResources(state, id, false,
        request.attachments.map { ResearchResource(it.id, it.name, attachment = it) }, explicit = true).state

    private fun imported(rootId: String, source: List<ChatSession>, generations: Map<String, Long> = emptyMap()): State {
        val sessions = source.map { original ->
            val checkpoints = listOfNotNull(original.pendingRun) + original.queuedPrompts
            val used = (original.messages.map { it.id } + checkpoints.flatMap { listOf(it.messageId, it.responseId, it.responseTimelineId) })
                .filter(String::isNotBlank).toMutableSet()
            fun missingIdentity(base: String): String {
                var candidate = base
                var suffix = 1
                while (!used.add(candidate)) candidate = "$base:${suffix++}"
                return candidate
            }
            fun migrate(request: CodingRunCheckpoint) = request.copy(
                responseId = request.responseId.ifBlank { missingIdentity("${request.runId}:reply") },
                responseTimelineId = request.responseTimelineId.ifBlank { missingIdentity("${request.runId}:timeline") })
            val session = original.copy(pendingRun = original.pendingRun?.let(::migrate), queuedPrompts = original.queuedPrompts.map(::migrate))
            if (session.pendingRun?.responseId?.let { id -> session.messages.any { it.id == id && it.role == ChatRole.AGENT } } == true)
                session.copy(pendingRun = null, pendingActivity = emptyList(), pendingContent = emptyList()) else session
        }
        val runs = sessions.mapNotNull { session -> session.pendingRun?.let { request -> session.id to Run(
            RunRef(session.id, request.runId, (generations[session.id] ?: 0L) + 1, request.messageId, request.responseId, request.responseTimelineId),
            Phase.UNKNOWN) } }.toMap()
        return State(rootId, initialized = true, sessions = sessions.associateBy { it.id }, runs = runs,
            generations = generations + runs.mapValues { it.value.ref.generation }, startedRuns = runs.values.map { runKey(it.ref.sessionId, it.ref.runId) }.toSet())
    }
    private fun validNotebook(rootId: String, sessions: List<ChatSession>): Boolean = rootId.isNotBlank() &&
        sessions.isNotEmpty() && sessions.map { it.id }.distinct().size == sessions.size &&
        sessions.count { it.researchParentId == null && it.id == rootId } == 1 &&
        sessions.all { it.id.isNotBlank() && it.researchChatId == rootId && it.messages.map { m -> m.id }.distinct().size == it.messages.size } &&
        sessions.first { it.id == rootId }.selectedQuestionId.let { selected -> selected == null || sessions.any { it.id == selected } }
    private fun validNewRequest(state: State, session: ChatSession, request: CodingRunCheckpoint, existingMessage: Boolean = false): Boolean {
        val identifiers = listOf(request.messageId, request.responseId, request.responseTimelineId)
        if (request.runId.isBlank() || identifiers.any(String::isBlank) || identifiers.distinct().size != identifiers.size ||
            request.prompt.isBlank() && request.attachments.isEmpty() || runKey(session.id, request.runId) in state.startedRuns) return false
        val existing = existingMessage && session.messages.any { it.id == request.messageId && it.role == ChatRole.USER }
        val checkpoints = listOfNotNull(session.pendingRun) + session.queuedPrompts
        if (checkpoints.any { it.runId == request.runId }) return false
        val reserved = session.messages.map { it.id } + checkpoints.flatMap { listOf(it.messageId, it.responseId, it.responseTimelineId) }
        return identifiers.none { id -> id in reserved && !(existing && id == request.messageId) }
    }
    private fun validReply(ref: RunRef, message: ChatMessage) = message.id == ref.responseId && message.role == ChatRole.AGENT
    private fun historyMutable(state: State, session: ChatSession) = session.id !in state.runs && session.pendingRun == null && session.queuedPrompts.isEmpty()
    private fun State.match(ref: RunRef): Run? = runs[ref.sessionId]?.takeIf { it.ref == ref }
    private fun State.withSession(session: ChatSession) = copy(sessions = sessions + (session.id to session))
    private fun State.withRun(run: Run) = copy(runs = runs + (run.ref.sessionId to run))
    private fun State.stopCheckpoint(id: String, byUser: Boolean): State {
        val session = sessions.getValue(id)
        return withSession(session.copy(pendingRun = session.pendingRun?.let { it.copy(intent = ExecutionIntent.STOP,
            stoppedByUser = it.stoppedByUser || byUser) }))
    }
    private fun runKey(sessionId: String, runId: String) = "${sessionId.length}:$sessionId$runId"
    private const val ARCHIVE_DELAY = 2 * 24 * 60 * 60 * 1000L
}
