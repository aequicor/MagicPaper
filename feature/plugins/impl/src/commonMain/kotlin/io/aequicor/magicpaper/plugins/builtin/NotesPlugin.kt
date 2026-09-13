package io.aequicor.magicpaper.plugins.builtin

import io.aequicor.magicpaper.designsystem.*

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import io.aequicor.magicpaper.data.storage.DraftRepository
import io.aequicor.magicpaper.data.storage.PersistentDraftValue
import io.aequicor.magicpaper.plugins.PersistentPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.builtins.serializer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.plugins.MagicPlugin

/** Плагин «Заметки»: простые заклинания памяти, живущие в состоянии. */
class NotesPlugin(repository: DraftRepository, applicationScope: CoroutineScope) : MagicPlugin, PersistentPlugin {
    private val owner = PersistentDraftValue(repository, "plugin:notes", String.serializer(), "", applicationScope)
    override suspend fun flushDrafts() = owner.flushDrafts()
    override suspend fun prepareForReset() = owner.prepareForReset()
    override fun resumeAfterReset() = owner.resumeAfterReset()
    override val id = "notes"
    override val title = "Заметки"
    override val description = "Короткие заклинания памяти рядом с чатом."
    override val icon = "✎"

    @Composable
    override fun Content() {
        val draft by owner.draft.state.collectAsState()
        val notes = draft.value
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            PaperText(icon + " " + title, style = LocalPaperTypography.current.title)
            if (draft.error != null) PaperText("Не удалось сохранить заметку.", color = LocalPaperColors.current.error)
            Spacer(Modifier.height(6.dp))
            PaperInput(
                value = notes,
                onValueChange = { value -> owner.update { value } },
                enabled = draft.loaded,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { PaperText("Записать мысль…") },
                minLines = 3,
                maxLines = 8,
            )

        }
    }
}
