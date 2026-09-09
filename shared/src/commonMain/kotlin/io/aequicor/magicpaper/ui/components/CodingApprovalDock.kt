package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.designsystem.PaperFonts
import io.aequicor.magicpaper.designsystem.*

/** Always visible above the composer, including requests from background planning workers. */
@Composable
internal fun CodingApprovalDock(
    approvals: List<CodingApproval>,
    onAnswer: (String, CodingApprovalDecision) -> Unit,
    onStop: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val request = approvals.firstOrNull() ?: return
    key(request.id) {
        PaperApprovalDock(modifier.padding(12.dp)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PaperText(request.title, role = PaperTextRole.TITLE, fontWeight = FontWeight.SemiBold)
            PaperText("Сессия «${request.sessionName}»" + if (approvals.size > 1) " · Ожидают решения: ${approvals.size}" else "", role = PaperTextRole.LABEL, color = LocalPaperColors.current.secondaryText)
            SelectionContainer(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaperText(request.reason)
                    PaperText(request.details, role = PaperTextRole.CODE)
                    if (request.kind == CodingApprovalKind.PERMISSIONS) PaperText("Доступ действует до завершения текущего запроса.")
                    if (!request.canAllow) PaperText("Движок не передал достаточно данных для разового разрешения. Можно отклонить действие.")
                }
            }
            request.error?.let { PaperText(it, color = LocalPaperColors.current.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (request.error != null) {
                    PaperButton("Остановить запрос", { onStop(request.sessionId) }, kind = PaperButtonKind.DESTRUCTIVE)
                } else {
                    PaperButton("Отклонить", { onAnswer(request.id, CodingApprovalDecision.DENY) }, enabled = !request.submitting, kind = PaperButtonKind.SECONDARY)
                    PaperButton(if (request.submitting) "Передаём решение…" else request.allowLabel, { onAnswer(request.id, CodingApprovalDecision.ALLOW_ONCE) }, enabled = request.canAllow && !request.submitting, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
        }
    }
}
