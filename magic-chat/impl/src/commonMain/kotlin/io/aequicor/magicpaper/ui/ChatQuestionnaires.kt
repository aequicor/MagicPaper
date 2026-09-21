package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.storage.DraftRepository
import io.aequicor.magicpaper.data.storage.DraftSession
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Application-owned drafts and explicit response delivery; opening a screen only observes requests. */
internal class ChatQuestionnaires(
    private val backend: ChatBackend,
    private val repository: DraftRepository,
    private val scope: CoroutineScope,
    private val notice: (String) -> Unit,
) {
    private data class Entry(val request: UserInteractionRequest, val identity: String, val session: DraftSession<QuestionnaireDraft>, var observer: Job? = null)
    private val json = Json { ignoreUnknownKeys = true }
    private val sessions = mutableMapOf<String, Entry>()
    private val submitting = mutableSetOf<String>()
    private val errors = mutableMapOf<String, String>()
    private val deletedSessions = mutableSetOf<String>()
    private val mutableRequests = MutableStateFlow<List<UserInteractionRequest>>(emptyList())
    val requests = mutableRequests.asStateFlow()
    private val mutableDrafts = MutableStateFlow<Map<String, QuestionnaireDraft>>(emptyMap())
    val drafts = mutableDrafts.asStateFlow()
    private var observer: Job? = null

    fun start() {
        if (observer != null) return
        observer = scope.launch {
            backend.questionnaires.collect { values ->
                values.filterNot { it.sessionId in deletedSessions }.forEach(::session)
                publish()
            }
        }
    }

    private fun identity(request: UserInteractionRequest) = json.encodeToString(listOf(request.id, request.sourceId,
        request.ownerSessionId, request.revision?.toString().orEmpty(), request.runtimeGeneration.toString(), request.runId,
        json.encodeToString(request.questions)))
    private fun prefix(sessionId: String) = "chat-questionnaire:${json.encodeToString(sessionId)}:"

    private fun session(request: UserInteractionRequest): Entry {
        val identity = identity(request)
        sessions[request.id]?.takeIf { it.identity == identity }?.let { return it }
        sessions.remove(request.id)?.let { it.session.revoke(); it.observer?.cancel() }
        errors.remove(request.id)
        val secretIds = request.questions.filter { it.secret }.map { it.id }.toSet()
        val draft = DraftSession(repository, prefix(request.sessionId) + identity, QuestionnaireDraft.serializer(),
            QuestionnaireDraft(request.initialAnswers), scope, json,
            redact = { value -> value.copy(answers = value.answers.map { if (it.questionId in secretIds) PlanningAnswer(it.questionId) else it }) },
            extractSecrets = { value -> value.answers.filter { it.questionId in secretIds }
                .associate { it.questionId to json.encodeToString(PlanningAnswer.serializer(), it) } },
            hydrateSecrets = { value, fields -> value.copy(answers = value.answers.map { answer ->
                fields[answer.questionId]?.let { json.decodeFromString(PlanningAnswer.serializer(), it) } ?: answer
            }) })
        val entry = Entry(request, identity, draft)
        sessions[request.id] = entry
        entry.observer = scope.launch {
            draft.state.collect { state ->
                if (sessions[request.id] === entry) {
                    mutableDrafts.update { it + (request.id to state.value) }
                    publish()
                }
            }
        }
        return entry
    }

    private fun publish() {
        mutableRequests.value = backend.questionnaires.value.filterNot { it.sessionId in deletedSessions }.map { request ->
            val draft = sessions[request.id]?.session?.state?.value
            request.copy(submitting = request.submitting || request.id in submitting,
                error = errors[request.id] ?: draft?.error?.let { "Не удалось сохранить ответы. Повторите отправку." } ?: request.error)
        }
    }

    fun updateDraft(id: String, draft: QuestionnaireDraft) {
        val request = requests.value.firstOrNull { it.id == id } ?: return
        if (request.submitting) return
        errors.remove(id)
        session(request).session.update(draft)
        publish()
    }

    fun submit(id: String, answers: List<PlanningAnswer>) {
        val request = requests.value.firstOrNull { it.id == id } ?: return
        if (request.submitting || !submitting.add(id)) return
        val entry = session(request)
        val version = entry.session.state.value.version
        errors.remove(id)
        publish()
        scope.launch {
            var delivered = false
            try {
                val fresh = backend.questionnaires.value.firstOrNull { it.id == id } ?: error("Request closed")
                require(identity(fresh) == entry.identity) { "Request changed" }
                validateInteractionAnswers(fresh.questions, answers)
                entry.session.awaitSaved()
                require(backend.questionnaires.value.any { it.id == id && identity(it) == entry.identity } &&
                    fresh.sessionId !in deletedSessions) { "Request changed" }
                backend.respondQuestionnaire(fresh.sourceId, answers)
                delivered = true
                entry.session.clearIfUnchanged(version)
                AppLog.info("chat", "questionnaire.answered", mapOf("requestId" to id, "sessionId" to fresh.sessionId))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppLog.error("chat", if (delivered) "questionnaire.cleanup.failed" else "questionnaire.answer.failed", failure,
                    mapOf("requestId" to id, "sessionId" to request.sessionId))
                if (delivered) notice("Ответ отправлен, но не удалось удалить черновик.")
                else errors[id] = "Не удалось отправить ответы. Проверьте их и повторите попытку."
            } finally { submitting.remove(id); publish() }
        }
    }

    suspend fun removeSession(id: String) {
        deletedSessions += id
        val owned = sessions.filterValues { it.request.sessionId == id }
        owned.forEach { (key, value) -> value.session.revoke(); value.observer?.cancel(); sessions.remove(key); errors.remove(key) }
        mutableDrafts.update { it - owned.keys }
        publish()
        try { repository.keys(prefix(id)).forEach { repository.remove(it) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            AppLog.error("chat", "questionnaire.drafts.remove.failed", failure, mapOf("sessionId" to id))
            notice("Чат удалён. Не удалось удалить черновики ответов.")
        }
    }

    suspend fun flush() { sessions.values.toList().forEach { it.session.awaitSaved() } }
    fun reset() {
        observer?.cancel(); observer = null
        sessions.values.forEach { it.session.revoke(); it.observer?.cancel() }
        sessions.clear(); errors.clear(); submitting.clear(); deletedSessions.clear()
        mutableRequests.value = emptyList(); mutableDrafts.value = emptyMap()
    }
}
