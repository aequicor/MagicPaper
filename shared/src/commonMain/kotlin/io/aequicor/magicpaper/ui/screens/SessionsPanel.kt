package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.LocalPaperColors
import io.aequicor.magicpaper.designsystem.PaperButton
import io.aequicor.magicpaper.designsystem.PaperButtonKind
import io.aequicor.magicpaper.designsystem.PaperChoice
import io.aequicor.magicpaper.designsystem.PaperDivider
import io.aequicor.magicpaper.designsystem.PaperIconButton
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperTextRole
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.ui.MagicPaperViewModel

/** Боковая панель со списком свитков. Выбор и удаление равнодоступны с клавиатуры. */
@Composable
fun SessionsPanel(vm: MagicPaperViewModel, sessions: List<ChatSession>, currentId: String?) {
    Column(Modifier.width(240.dp).fillMaxHeight()) {
        PaperText("Свитки", role = PaperTextRole.TITLE, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        PaperDivider()
        LazyColumn(Modifier.weight(1f)) {
            items(sessions, key = { it.id }) { session ->
                SessionRow(session, session.id == currentId, { vm.selectSession(session.id) }, { vm.deleteSession(session.id) })
            }
        }
        PaperButton("✦ Новый свиток", vm::newSession, Modifier.padding(8.dp), kind = PaperButtonKind.QUIET)
    }
}

@Composable
private fun SessionRow(session: ChatSession, selected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        PaperChoice(selected, onClick, session.title, Modifier.weight(1f))
        PaperIconButton("Удалить свиток ${session.title}", onDelete) {
            PaperText("✕", role = PaperTextRole.LABEL, color = LocalPaperColors.current.secondaryText)
        }
    }
}
