package io.aequicor.magicpaper.domain

/** Аудит-действие, которым приложение или пользователь снимает ранее сохранённый карантин. */
const val QUARANTINE_ACTION: String = "QUARANTINE"
const val QUARANTINE_RESOLVED_ACTION: String = "RECONCILE_QUARANTINE"

/** Идентификатор снятия карантина. Один и тот же ключ используют хранилище и интерфейс. */
fun quarantineResolutionId(sessionId: String, quarantineOperationId: String): String =
    "resolved-$sessionId-$quarantineOperationId"

/** Карантины, затрагивающие [sessionId] и не закрытые собственным подтверждением исхода. */
fun SessionOrganism.pendingQuarantines(sessionId: String): List<SessionAuditEvent> = audit.filter { event ->
    event.action == QUARANTINE_ACTION && sessionId in event.affected && audit.none { resolution ->
        resolution.action == QUARANTINE_RESOLVED_ACTION &&
            resolution.operationId == quarantineResolutionId(sessionId, event.operationId)
    }
}
