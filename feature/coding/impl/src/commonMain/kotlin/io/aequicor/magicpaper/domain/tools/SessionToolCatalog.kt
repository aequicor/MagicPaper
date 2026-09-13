package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.Serializable

@Serializable data class SessionCreateArgs(val name: String, val task: String, val acceptance: String, val tokens: Long = 0,
    val sourceVersion: String = "", val dependencies: Set<String> = emptySet(), val failurePolicy: SessionFailurePolicy = SessionFailurePolicy.ISOLATE,
    val expectedVersion: Long? = null)
@Serializable data class SessionSendArgs(val sessionId: String, val context: SessionContextPacket, val expectedVersion: Long? = null)
@Serializable data class SessionControlArgs(val sessionId: String, val action: OrganismAction, val reason: String,
    val tokens: Long = 0, val name: String = "", val expectedVersion: Long? = null)
@Serializable data class SessionWaitArgs(val sessionIds: Set<String>, val expectedVersion: Long? = null)
@Serializable data class ImmunitySignalArgs(val sessionId: String, val diagnostic: String, val expectedVersion: Long? = null)
@Serializable data class SessionRouteArgs(val sourceSessionId: String, val targetSessionId: String, val reason: String,
    val expectedVersion: Long? = null)
@Serializable data class SessionReviewResultArgs(val resultId: String, val accepted: Boolean, val evidence: String,
    val sourceVersion: String, val checks: List<String>, val expectedVersion: Long? = null)
@Serializable data class SessionResultGetArgs(val resultId: String)
@Serializable data class SessionIntegrateArgs(val resultIds: List<String>, val checks: List<List<String>>, val expectedVersion: Long? = null)
@Serializable data class SessionIntegrationGetArgs(val integrationId: String, val offset: Int = 0, val limit: Int = 16_000)
@Serializable data class ToolReceiptGetArgs(val receiptId: String, val offset: Int = 0, val limit: Int = 16_000)

/** Every ordinary session receives the same orchestration surface; the host checks authority. */
object SessionToolCatalog {
    val definitions = listOf(
        ToolDefinition("session.create", "Создать непосредственную дочернюю сессию. Она разделяет общий бюджет задачи, наследует режим и правила. Ограничения задаёт пользователь в настройках; tokens можно не указывать.", toolSchema(SessionCreateArgs.serializer().descriptor), mutating = true),
        ToolDefinition("session.send", "Передать сохранённый контекст родителю, ребёнку или по разрешённому общим предком маршруту. Для другой ветки запросите маршрут у родителя.", toolSchema(SessionSendArgs.serializer().descriptor), mutating = true),
        ToolDefinition("session.control", "Управлять непосредственным ребёнком: STOP, ARCHIVE, RESTORE, PAUSE, QUARANTINE, RENAME. Остановка подтверждается приложением.", toolSchema(SessionControlArgs.serializer().descriptor), mutating = true),
        ToolDefinition("session.wait", "Дождаться непосредственных детей. Приложение проверяет зависимости и циклы ожидания.", toolSchema(SessionWaitArgs.serializer().descriptor), mutating = true),
        ToolDefinition("session.route", "Разрешить маршрут передачи контекста между своими ветками как их общий предок; приложение сохраняет происхождение и каждый переход.", toolSchema(SessionRouteArgs.serializer().descriptor), mutating = true),
        ToolDefinition("session.result.review", "Принять или отклонить результат непосредственного ребёнка, указав проверенные основания, версию исходников и проверки.", toolSchema(SessionReviewResultArgs.serializer().descriptor), mutating = true),
        ToolDefinition("session.result.get", "Прочитать свой результат или результат, назначенный вам для проверки.", toolSchema(SessionResultGetArgs.serializer().descriptor), category = ToolCategory.READ),
        ToolDefinition("session.results.integrate", "Объединить принятые CODE-результаты своих детей в отдельной Git-копии и выполнить итоговые проверки. checks содержит массивы аргументов команд. Возвращает сохранённый результат и SHA; пользовательская папка не меняется. Требуется режим разработки.", toolSchema(SessionIntegrateArgs.serializer().descriptor), mutating = true),
        ToolDefinition("session.integration.get", "Прочитать сохранённую интеграцию и фактические результаты её проверок по частям.", toolSchema(SessionIntegrationGetArgs.serializer().descriptor), category = ToolCategory.READ),
        ToolDefinition("immunity.signal", "Передать диагностический сигнал иммунитету своего организма независимо от состояния родителя. Жалоба не доказывает неисправность.", toolSchema(ImmunitySignalArgs.serializer().descriptor), mutating = true),
        ToolDefinition("receipt.get", "Прочитать сохранённые аргументы, состояние и результат своего вызова инструмента по частям.", toolSchema(ToolReceiptGetArgs.serializer().descriptor), category = ToolCategory.READ),
    ).map { definition ->
        if (!definition.mutating) definition else definition.copy(description = definition.description +
            " expectedVersion — organismVersion из context.get; устаревшая версия отклоняется. Если поле опущено, приложение проверяет версию, прочитанную при отправке команды.")
    }
}
