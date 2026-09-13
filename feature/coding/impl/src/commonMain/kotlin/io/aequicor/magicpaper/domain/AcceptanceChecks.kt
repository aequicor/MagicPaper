package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.util.Id

class AcceptanceChecks(private val checks: Map<String, RegisteredAcceptanceCheck> = emptyMap()) {
    internal fun supports(criterion: AcceptanceCriterion): Boolean = criterion.environment == EvidenceEnvironment.REVIEW ||
        checks[criterion.checkId]?.environment == criterion.environment

    internal fun planningCatalog(): String = checks.entries.joinToString("\n") { "${it.key}: ${it.value.environment}" }
        .ifBlank { "Зарегистрированных проверок приложения нет." }

    internal fun executionGuidance(): String = """
        Для проверок используй только инструменты, доступные в текущем вызове, и существующий toolchain проекта.
        Реестр проверок приложения:
        ${planningCatalog()}
        Этот реестр фиксирован приложением: исполнитель не может зарегистрировать checkId или добавить инструмент в рантайме.
        REVIEW допускает подтверждение командами, результатами тестов и ссылками на код из отчёта; отдельная проверяющая функция приложения для него не нужна.
        Не требуй отсутствующий инструмент и не поручай создать его ради приёмки. Если обязательная проверка недоступна, сообщи конкретное ограничение один раз; повтор исполнителя его не устраняет.
    """.trimIndent()

    suspend fun collect(criteria: List<AcceptanceCriterion>, path: String, snapshotId: String): List<AcceptanceEvidence> =
        criteria.filter { it.environment != EvidenceEnvironment.REVIEW }.map { criterion ->
            val check = checks[criterion.checkId]?.takeIf { it.environment == criterion.environment }
            val result = check?.run?.invoke(path) ?: AcceptanceCheckResult(CheckStatus.NOT_RUN,
                "Для ${criterion.environment} не зарегистрирована проверка ${criterion.checkId.ifBlank { "(ID отсутствует)" }}")
            AcceptanceEvidence(criterion.id, criterion.environment, snapshotId, result.status, result.detail, result.artifacts)
        }
}