package io.aequicor.magicpaper.plugins.builtin

import io.aequicor.magicpaper.designsystem.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.data.storage.DraftRepository
import io.aequicor.magicpaper.plugins.MagicPlugin
import io.aequicor.magicpaper.plugins.PersistentPlugin
import io.aequicor.magicpaper.data.storage.PersistentDraftValue
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
private data class FocusDraft(val remaining: Int = 25 * 60, val deadline: Long? = null)

/** The deadline, rather than a UI coroutine, owns a running timer across navigation and restart. */
class FocusPlugin(repository: DraftRepository, applicationScope: CoroutineScope) : MagicPlugin, PersistentPlugin {
    override val id = "focus"
    override val title = "Фокус"
    override val description = "Таймер концентрации на 25 минут."
    override val icon = "◷"
    private val owner = PersistentDraftValue(repository, "plugin:focus", FocusDraft.serializer(), FocusDraft(), applicationScope)
    private var now by mutableStateOf(Id.now())
    init { applicationScope.launch {
        while (true) {
            now = Id.now()
            if (!owner.paused) {
                val state = owner.draft.state.value
                if (state.loaded && state.value.deadline?.let { it <= now } == true) owner.update { it.copy(remaining = 0, deadline = null) }
            }
            delay(1_000)
        }
    } }
    override suspend fun flushDrafts() = owner.flushDrafts()
    override suspend fun prepareForReset() = owner.prepareForReset()
    override fun resumeAfterReset() = owner.resumeAfterReset()
    @Composable override fun Content() {
        val draft by owner.draft.state.collectAsState()
        val running = draft.value.deadline != null
        val remaining = draft.value.deadline?.let { ((it - now + 999) / 1000).coerceAtLeast(0).toInt() } ?: draft.value.remaining
        Column(Modifier.padding(vertical = 8.dp)) {
            PaperText("$icon $title", style = LocalPaperTypography.current.title)
            if (draft.error != null) PaperText("Не удалось сохранить таймер.", color = LocalPaperColors.current.error)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PaperText("${(remaining / 60).toString().padStart(2, '0')}:${(remaining % 60).toString().padStart(2, '0')}",
                    style = LocalPaperTypography.current.headline, color = LocalPaperColors.current.action)
                Spacer(Modifier.width(12.dp))
                PaperButton(if (running) "Пауза" else "Старт", enabled = draft.loaded && remaining > 0,
                    onClick = { owner.update { if (running) FocusDraft(remaining) else FocusDraft(remaining, Id.now() + remaining * 1000L) } })
                PaperButton("Сброс", enabled = draft.loaded, kind = PaperButtonKind.QUIET, onClick = { owner.update { FocusDraft() } })
            }
        }
    }
}
