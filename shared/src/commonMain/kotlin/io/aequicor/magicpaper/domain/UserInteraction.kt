package io.aequicor.magicpaper.domain

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** Actions come from the host, never from tool arguments or generated question text. */
@Serializable
enum class InteractionKind { QUESTION, RUNTIME, APPROVAL, CONFIRM_PLAN, RECOVER_PLAN, RECOVER_INPUT, RECOVER_STORAGE, RECOVER_RUN, RECOVER_DECISIONS }

@Serializable
data class UserInteractionRequest(
    val id: String,
    val projectId: String,
    val sessionId: String,
    val kind: InteractionKind,
    val questions: List<PlanningQuestion>,
    val sourceId: String = id,
    val ownerSessionId: String = sessionId,
    val affectedSessionIds: Set<String> = setOf(sessionId, ownerSessionId),
    val context: String = "",
    val details: String = "",
    val planId: String? = null,
    val revision: Long? = null,
    val createdAt: Long = 0,
    val initialAnswers: List<PlanningAnswer> = emptyList(),
    val submitting: Boolean = false,
    val error: String? = null,
    val outcomeUnknown: Boolean = false,
    val runtimeGeneration: Long = 0,
    val runId: String = "",
) {
    fun affects(session: CodingSession) = projectId == session.projectId && !session.archived && session.id in affectedSessionIds
}

@Serializable
data class QuestionnaireDraft(val answers: List<PlanningAnswer> = emptyList(), val index: Int = 0, val reviewing: Boolean = false)

fun PlanningAnswer.isComplete(question: PlanningQuestion): Boolean =
    if (skipped) question.canSkip && selected.isEmpty() && text.isBlank()
    else (selected.isNotEmpty() || text.isNotBlank()) &&
        (question.allowCustomInput || text.isBlank()) &&
        (question.kind != QuestionKind.SINGLE || selected.distinct().size <= 1) &&
        selected.distinct().size == selected.size && selected.all { id -> question.options.any { it.id == id && it.enabled } }

fun validateInteractionAnswers(questions: List<PlanningQuestion>, answers: List<PlanningAnswer>) {
    require(questions.isNotEmpty() && questions.map { it.id }.distinct().size == questions.size) { "Некорректный список вопросов" }
    require(answers.size == questions.size && answers.map { it.questionId }.toSet() == questions.map { it.id }.toSet()) { "Пройдите все вопросы" }
    require(questions.all { q -> answers.single { it.questionId == q.id }.isComplete(q) }) { "Проверьте ответы" }
}

fun interactionAnswerText(questions: List<PlanningQuestion>, answers: List<PlanningAnswer>, redactSecrets: Boolean = false): String =
    questions.joinToString("\n\n") { q ->
        val a = answers.firstOrNull { it.questionId == q.id }
        q.title + "\n" + when {
            a == null -> "Без ответа"
            a.skipped -> "Пропущено пользователем"
            q.secret && redactSecrets -> "Ответ скрыт"
            else -> (q.options.filter { it.id in a.selected }.map { it.label } + listOf(a.text).filter { it.isNotBlank() }).joinToString("; ")
        }
    }

/** Arrival order is stable even when telemetry refreshes a request or another source becomes available. */
class UserInteractionQueue {
    private var order = emptyList<String>()
    fun reconcile(candidates: List<UserInteractionRequest>, dismissed: Set<String> = emptySet()): List<UserInteractionRequest> {
        val current = candidates.filter { it.id !in dismissed && it.questions.isNotEmpty() }.associateBy { it.id }
        order = order.filter { it in current } + current.values.filter { it.id !in order }.sortedBy { it.createdAt }.map { it.id }
        return order.map { current.getValue(it) }
    }
}

@Serializable
enum class RuntimeQuestionnaireStatus { OPEN, ANSWERED, DELIVERED, CANCELLED, INTERRUPTED }

@Serializable
data class RuntimeQuestionnaireRecord(
    val request: UserInteractionRequest,
    val status: RuntimeQuestionnaireStatus = RuntimeQuestionnaireStatus.OPEN,
    val answers: List<PlanningAnswer> = emptyList(),
    val answersRedacted: Boolean = false,
)

/** One replacement is the transaction boundary; never complete a waiter before it succeeds. */
interface RuntimeQuestionnaireStore {
    fun load(): List<RuntimeQuestionnaireRecord>
    fun save(records: List<RuntimeQuestionnaireRecord>)
}

/** Durable identity and answers are independent from the coroutine/transport waiting for them. */
class RuntimeQuestionnaires(private val storage: RuntimeQuestionnaireStore? = null, private val maxPending: Int = 64) {
    private val lock = Mutex()
    private val pending = mutableMapOf<String, CompletableDeferred<List<PlanningAnswer>>>()
    private var storageUncertain = false
    private var records = storage?.load().orEmpty().associateBy { it.request.id }.mapValues { (_, record) ->
        // No persisted OPEN request proves the original transport is still connected after a crash.
        if (record.status == RuntimeQuestionnaireStatus.OPEN) record.copy(status = RuntimeQuestionnaireStatus.INTERRUPTED) else record
    }
    private val state = MutableStateFlow<List<UserInteractionRequest>>(emptyList())
    val requests = state.asStateFlow()
    private val historyState = MutableStateFlow(records.values.toList())
    val history = historyState.asStateFlow()

    private fun persist(next: Map<String, RuntimeQuestionnaireRecord>) {
        check(!storageUncertain) { "Состояние хранилища опросников не подтверждено; требуется восстановление" }
        try { storage?.save(next.values.toList()) }
        catch (failure: Exception) {
            // A filesystem can fail after rename. Verify that exact snapshot instead of overwriting
            // a possibly committed answer from our older in-memory state during cancellation.
            val observed = runCatching { storage?.load()?.associateBy { it.request.id } }.getOrNull()
            if (observed != next) {
                if (observed == null || observed != records) storageUncertain = true
                throw failure
            }
        }
        records = next
        historyState.value = next.values.toList()
    }

    private fun sameCall(a: UserInteractionRequest, b: UserInteractionRequest) =
        a.projectId == b.projectId && a.sessionId == b.sessionId && a.ownerSessionId == b.ownerSessionId &&
            a.sourceId == b.sourceId && a.kind == b.kind && a.questions == b.questions &&
            a.runtimeGeneration == b.runtimeGeneration && a.runId == b.runId

    suspend fun ask(request: UserInteractionRequest): List<PlanningAnswer> {
        val result = CompletableDeferred<List<PlanningAnswer>>()
        lock.withLock {
            require(request.id !in pending) { "Запрос уже существует" }
            require(request.questions.isNotEmpty() && request.questions.size <= 64 &&
                request.questions.map { it.id }.distinct().size == request.questions.size) { "Некорректное число вопросов" }
            require(pending.size < maxPending) { "Достигнут лимит ожидающих опросников" }
            val saved = records[request.id]
            require(saved == null || sameCall(saved.request, request)) { "Идентификатор вопроса принадлежит другому вызову" }
            check(saved?.status != RuntimeQuestionnaireStatus.CANCELLED) { "Обращение отменено" }
            if (saved?.status in setOf(RuntimeQuestionnaireStatus.ANSWERED, RuntimeQuestionnaireStatus.DELIVERED) && !saved!!.answersRedacted)
                return saved.answers
            val secretIds = request.questions.filter { it.secret }.map { it.id }.toSet()
            persist(records + (request.id to RuntimeQuestionnaireRecord(request.copy(initialAnswers =
                request.initialAnswers.map { if (it.questionId in secretIds) PlanningAnswer(it.questionId) else it }))))
            pending[request.id] = result
            state.value = state.value + request.copy(initialAnswers = saved?.answers.orEmpty())
        }
        return try { result.await() } finally {
            withContext(NonCancellable) { lock.withLock {
                pending.remove(request.id)
                state.value = state.value.filterNot { it.id == request.id }
                records[request.id]?.takeIf { it.status == RuntimeQuestionnaireStatus.OPEN }?.let { record ->
                    persist(records + (request.id to record.copy(status = RuntimeQuestionnaireStatus.CANCELLED)))
                }
            } }
        }
    }

    suspend fun respond(id: String, answers: List<PlanningAnswer>) = lock.withLock {
        val request = state.value.firstOrNull { it.id == id } ?: error("Обращение уже закрыто")
        validateInteractionAnswers(request.questions, answers)
        val secretIds = request.questions.filter { it.secret }.map { it.id }.toSet()
        persist(records + (id to records.getValue(id).copy(status = RuntimeQuestionnaireStatus.ANSWERED,
            answers = answers.map { if (it.questionId in secretIds) PlanningAnswer(it.questionId) else it },
            answersRedacted = secretIds.isNotEmpty())))
        check(pending.getValue(id).complete(answers)) { "Ответ уже отправлен" }
        state.value = state.value.filterNot { it.id == id }
    }

    suspend fun acknowledgeDelivery(id: String) = lock.withLock {
        val record = records[id] ?: error("Обращение не найдено")
        check(record.status in setOf(RuntimeQuestionnaireStatus.ANSWERED, RuntimeQuestionnaireStatus.DELIVERED)) { "Ответ ещё не подтверждён" }
        if (record.status != RuntimeQuestionnaireStatus.DELIVERED)
            persist(records + (id to record.copy(status = RuntimeQuestionnaireStatus.DELIVERED)))
    }

    /** Explicit revocation prevents an old answer/operation from attaching to a replacement run. */
    suspend fun revoke(sessionId: String, generation: Long? = null) = lock.withLock {
        val ids = records.values.filter { it.request.sessionId == sessionId &&
            (generation == null || it.request.runtimeGeneration == generation) &&
            it.status !in setOf(RuntimeQuestionnaireStatus.DELIVERED, RuntimeQuestionnaireStatus.CANCELLED) }.map { it.request.id }.toSet()
        persist(records.mapValues { (id, record) -> if (id in ids) record.copy(status = RuntimeQuestionnaireStatus.CANCELLED) else record })
        ids.forEach { pending[it]?.cancel(CancellationException("Сессия остановлена")) }
        state.value = state.value.filterNot { it.id in ids }
    }
}

internal val noRuntimeQuestionnaires = MutableStateFlow<List<UserInteractionRequest>>(emptyList()).asStateFlow()
