package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.theme.MagicFonts

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
        Column(modifier.background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(request.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Сессия «${request.sessionName}»" + if (approvals.size > 1) " · Ожидают решения: ${approvals.size}" else "",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(request.reason, style = MaterialTheme.typography.bodySmall)
                    Text(request.details, style = MaterialTheme.typography.bodySmall, fontFamily = MagicFonts.code)
                    if (request.kind == CodingApprovalKind.PERMISSIONS) Text("Доступ действует до завершения текущего запроса.", style = MaterialTheme.typography.bodySmall)
                    if (!request.canAllow) Text("Движок не передал достаточно данных для разового разрешения. Можно отклонить действие.", style = MaterialTheme.typography.bodySmall)
                }
            }
            request.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (request.error != null) {
                    TextButton(onClick = { onStop(request.sessionId) }) { Text("Остановить запрос") }
                } else {
                    OutlinedButton(onClick = { onAnswer(request.id, CodingApprovalDecision.DENY) }, enabled = !request.submitting) { Text("Отклонить") }
                    Button(onClick = { onAnswer(request.id, CodingApprovalDecision.ALLOW_ONCE) }, enabled = request.canAllow && !request.submitting,
                        modifier = Modifier.weight(1f, fill = false)) { Text(if (request.submitting) "Передаём решение…" else request.allowLabel) }
                }
            }
        }
    }
}
