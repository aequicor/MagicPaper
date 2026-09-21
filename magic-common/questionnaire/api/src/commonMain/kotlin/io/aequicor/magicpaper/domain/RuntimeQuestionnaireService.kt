package io.aequicor.magicpaper.domain

import kotlinx.coroutines.flow.StateFlow

/** Application-lifetime owner; restoring history never opens a request or sends an answer. */
interface RuntimeQuestionnaireService {
    val requests: StateFlow<List<UserInteractionRequest>>
    val history: StateFlow<List<RuntimeQuestionnaireRecord>>
    val persistence: StateFlow<QuestionnairePersistence>
    suspend fun start()
    /** Read durable evidence after an uncertain write; never repeats external delivery. */
    suspend fun recover()
    suspend fun ask(request: UserInteractionRequest): List<PlanningAnswer>
    suspend fun respond(id: String, answers: List<PlanningAnswer>)
    /** Must complete durably before the transport writes any answer bytes. */
    suspend fun beginDelivery(id: String): String
    /** CONFIRMED requires affirmative protocol evidence, not a successful local write. */
    suspend fun finishDelivery(id: String, attemptId: String, outcome: QuestionnaireDeliveryOutcome)
    suspend fun revoke(sessionId: String, generation: Long? = null)
    suspend fun clearForReset()
}

/** A factory returns the same application service for repeated calls with one namespace. */
interface RuntimeQuestionnaireFactory {
    fun create(namespace: String, legacyStore: RuntimeQuestionnaireStore?): RuntimeQuestionnaireService
    /** After producers join, before the application drops journals and legacy storage. */
    suspend fun clearForReset()
}

/** Safe user-facing context; the original store failure remains available to its owning diagnostic boundary. */
class QuestionnairePersistenceException(cause: Throwable) : IllegalStateException(
    "Не удалось подтвердить сохранение опросника. Перезапустите приложение для восстановления.", cause)
