package io.aequicor.magicpaper.domain

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** Actions come from the host, never from tool arguments or generated question text. */
enum class InteractionKind { QUESTION, RUNTIME, APPROVAL, CONFIRM_PLAN, RECOVER_PLAN, RECOVER_INPUT, RECOVER_STORAGE, RECOVER_RUN, RECOVER_DECISIONS }

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

/** A live request exists only as long as its originating coroutine/connection. */
class RuntimeQuestionnaires {
    private val lock = Mutex()
    private val pending = mutableMapOf<String, CompletableDeferred<List<PlanningAnswer>>>()
    private val state = MutableStateFlow<List<UserInteractionRequest>>(emptyList())
    val requests = state.asStateFlow()

    suspend fun ask(request: UserInteractionRequest): List<PlanningAnswer> {
        val result = CompletableDeferred<List<PlanningAnswer>>()
        lock.withLock {
            require(request.id !in pending) { "Запрос уже существует" }
            pending[request.id] = result
            state.value = state.value + request
        }
        return try { result.await() } finally {
            withContext(NonCancellable) { lock.withLock {
                pending.remove(request.id)
                state.value = state.value.filterNot { it.id == request.id }
            } }
        }
    }

    suspend fun respond(id: String, answers: List<PlanningAnswer>) = lock.withLock {
        val request = state.value.firstOrNull { it.id == id } ?: error("Обращение уже закрыто")
        validateInteractionAnswers(request.questions, answers)
        check(pending.getValue(id).complete(answers)) { "Ответ уже отправлен" }
        state.value = state.value.filterNot { it.id == id }
    }
}

internal val noRuntimeQuestionnaires = MutableStateFlow<List<UserInteractionRequest>>(emptyList()).asStateFlow()
