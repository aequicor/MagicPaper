package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.launch

/** Message identity scopes the editor; services own the durable work beyond this composition. */
@Composable
internal fun MessageHistoryActions(
    messageId: String,
    text: String,
    copyText: () -> String,
    enabled: Boolean,
    onEdit: (suspend (String) -> Result<Unit>)? = null,
    onDelete: (suspend () -> Result<Unit>)? = null,
    onFork: (suspend () -> Result<String>)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var dialog by rememberSaveable(messageId) { mutableStateOf<String?>(null) }
    var edited by rememberSaveable(messageId) { mutableStateOf(text) }
    var busy by remember(messageId) { mutableStateOf(false) }
    var error by remember(messageId) { mutableStateOf<String?>(null) }
    PaperMessageActions(
        onCopy = {
            try { clipboard.setText(AnnotatedString(copyText())); error = null }
            catch (failure: Exception) {
                AppLog.error("session", "message.copy.failed", failure, mapOf("messageId" to messageId))
                error = "Не удалось скопировать сообщение. Повторите попытку."
            }
        },
        onEdit = onEdit?.let { { edited = text; error = null; dialog = "edit" } },
        onDelete = onDelete?.let { { error = null; dialog = "delete" } },
        onFork = onFork?.let { action -> { scope.launch {
            busy = true
            try { error = action().exceptionOrNull()?.message } finally { busy = false }
        }; Unit } },
        historyEnabled = enabled && !busy,
        forkEnabled = !busy,
    )
    if (dialog == null) error?.let { PaperText(it, color = LocalPaperColors.current.error, role = PaperTextRole.LABEL) }
    dialog?.let { mode ->
        val editing = mode == "edit"
        PaperDialog(if (editing) "Редактировать сообщение" else "Удалить сообщение?",
            onDismissRequest = { if (!busy) dialog = null },
            confirmLabel = if (editing) "Сохранить и отправить" else "Удалить",
            confirmEnabled = enabled && !busy && (!editing || edited.isNotBlank()), dismissEnabled = !busy,
            dismissLabel = "Отмена", onConfirm = {
                scope.launch {
                    busy = true
                    try {
                        val result = if (editing) checkNotNull(onEdit)(edited) else checkNotNull(onDelete)()
                        if (result.isSuccess) dialog = null else error = result.exceptionOrNull()?.message
                    } finally { busy = false }
                }
            }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (editing) PaperField(edited, { edited = it }, "Сообщение", singleLine = false,
                    enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp))
                PaperText(if (editing) "Сообщения после этого будут удалены. Агент ответит заново."
                    else "Сообщение будет удалено из истории и дальнейшего контекста агента.")
                PaperText("Уже выполненные действия и изменения файлов сохранятся.", role = PaperTextRole.LABEL)
                error?.let { PaperText(it, color = LocalPaperColors.current.error, role = PaperTextRole.LABEL) }
            }
        }
    }
}

@Composable
internal fun ForkSessionAction(enabled: Boolean, onFork: suspend () -> Result<String>) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    PaperButton("Форк сессии", enabled = enabled && !busy, kind = PaperButtonKind.QUIET, onClick = {
        scope.launch {
            busy = true
            try { error = onFork().exceptionOrNull()?.message } finally { busy = false }
        }
    })
    error?.let { PaperText(it, role = PaperTextRole.LABEL, color = LocalPaperColors.current.error) }
}
