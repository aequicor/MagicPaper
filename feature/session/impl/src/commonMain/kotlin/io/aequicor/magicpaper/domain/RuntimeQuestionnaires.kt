package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.util.Id

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

    /** Called only after native/model producers have joined their cancellation cleanup. */
    suspend fun clearForReset() = lock.withLock {
        check(pending.isEmpty()) { "Дождитесь закрытия опросников" }
        storage?.save(emptyList())
        records = emptyMap()
        state.value = emptyList()
        historyState.value = emptyList()
        storageUncertain = false
    }

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