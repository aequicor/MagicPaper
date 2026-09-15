package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.QuarantineRecoveryState

/**
 * Восстановление сессии, остановленной из-за неизвестного исхода операции. Триггеры живут там,
 * где пользователь видит блокировку (панель над диалогом и строка статуса), а диалог один:
 * приложение сначала сверяет журнал движка, а недоказуемый исход снимает только отдельное
 * подтверждение человека. Прерванный вызов никогда не выполняется повторно.
 */
@Composable
internal fun SessionQuarantineRecoveryTrigger(
    organism: SessionOrganism,
    sessionId: String,
    state: QuarantineRecoveryState = QuarantineRecoveryState(),
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (organism.deletedAt != null || sessionId in organism.historyDeletedIds) return
    if (organism.pendingQuarantines(sessionId).isEmpty()) return
    val unproven = sessionId in state.awaitingConfirmation
    val busy = sessionId in state.busy
    PaperButton(if (unproven) "Исход операции не доказан" else "Исход операции не подтверждён", onClick,
        modifier.fillMaxWidth(), kind = if (unproven) PaperButtonKind.SECONDARY else PaperButtonKind.PRIMARY,
        enabled = !busy, busy = busy,
        accessibilityLabel = "Восстановление сессии: исход прерванной операции не подтверждён")
}

@Composable
internal fun SessionQuarantineRecoveryDialog(
    organism: SessionOrganism,
    sessionId: String,
    state: QuarantineRecoveryState = QuarantineRecoveryState(),
    open: Boolean,
    onDismiss: () -> Unit,
    onReconcile: (confirmed: Boolean) -> Unit,
) {
    if (!open) return
    val quarantines = organism.pendingQuarantines(sessionId)
    if (quarantines.isEmpty()) return
    val spacing = LocalPaperSpacing.current
    val busy = sessionId in state.busy
    val unproven = sessionId in state.awaitingConfirmation
    var checked by rememberSaveable(organism.id, sessionId) { mutableStateOf(false) }
    val focus = remember(organism.id, sessionId) { PaperFocusRestorer() }
    PaperDialog("Восстановление после прерванной операции", onDismiss,
        modifier = Modifier.widthIn(max = 640.dp).heightIn(max = 650.dp), dismissLabel = "Закрыть",
        focusRestorer = focus) {
        PaperScrollArea(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                PaperText("Новые запросы заблокированы, пока исход этих операций не сверен:", role = PaperTextRole.TITLE)
                quarantines.forEach { event -> PaperText(event.reason, role = PaperTextRole.LABEL) }
                PaperText("Сверка читает журнал движка и останавливает предыдущий процесс. Прерванный вызов не выполняется повторно.")
                PaperButton("Сверить исход", { onReconcile(false) }, Modifier.fillMaxWidth(),
                    kind = PaperButtonKind.PRIMARY, enabled = !busy, busy = busy,
                    accessibilityLabel = "Сверить фактический исход прерванных операций")
                if (unproven) {
                    PaperDivider()
                    PaperText("Журнал движка не подтверждает исход. Проверьте сами изменённые файлы, состояние Git и внешние сервисы.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PaperCheck(checked, { checked = it }, enabled = !busy)
                        PaperText("Я проверил(а) фактический результат и снимаю блокировку")
                    }
                    PaperButton("Снять блокировку", { if (checked && !busy) onReconcile(true) }, Modifier.fillMaxWidth(),
                        kind = PaperButtonKind.DESTRUCTIVE, enabled = checked && !busy, busy = busy,
                        accessibilityLabel = "Снять блокировку после собственного подтверждения результата")
                }
            }
        }
    }
}

/** Панель над диалогом: собственный триггер плюс общий диалог восстановления. */
@Composable
internal fun SessionQuarantineRecovery(
    organism: SessionOrganism,
    sessionId: String,
    state: QuarantineRecoveryState = QuarantineRecoveryState(),
    onReconcile: (confirmed: Boolean) -> Unit,
) {
    if (organism.deletedAt != null || sessionId in organism.historyDeletedIds) return
    if (organism.pendingQuarantines(sessionId).isEmpty()) return
    val spacing = LocalPaperSpacing.current
    var open by rememberSaveable(organism.id, sessionId) { mutableStateOf(false) }
    val revealToken = state.reveal[sessionId]
    LaunchedEffect(revealToken) { if (revealToken != null) open = true }
    PaperPanel(Modifier.fillMaxWidth().padding(spacing.xs), kind = PaperSurfaceKind.RAISED) {
        PaperFocusAnchor(remember(organism.id, sessionId) { PaperFocusRestorer() }, "quarantine-$sessionId") {
            SessionQuarantineRecoveryTrigger(organism, sessionId, state, Modifier.padding(spacing.xs)) { open = true }
        }
    }
    SessionQuarantineRecoveryDialog(organism, sessionId, state, open, { open = false }, onReconcile)
}
