package io.aequicor.magicpaper.plugins.builtin

import io.aequicor.magicpaper.designsystem.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.data.skills.SkillStore
import io.aequicor.magicpaper.domain.ChatRepository
import io.aequicor.magicpaper.domain.LlmProfileRepository
import io.aequicor.magicpaper.domain.ProfileResolver
import io.aequicor.magicpaper.domain.Skill
import io.aequicor.magicpaper.domain.SkillDraft
import io.aequicor.magicpaper.domain.SkillEducator
import io.aequicor.magicpaper.domain.SkillInstaller
import io.aequicor.magicpaper.domain.SkillSource
import io.aequicor.magicpaper.domain.SettingsRepository
import io.aequicor.magicpaper.plugins.MagicPlugin
import kotlinx.coroutines.launch

/**
 * Плагин «Самообучение»: агент сам создаёт для себя навыки из диалога.
 * Схема «мозг, который тренируется»: успешный подход → черновик навыка →
 * подтверждение пользователя → навык в библиотеке → агент применяет его
 * в следующих разговорах. Черновик редактируется перед сохранением —
 * это и есть само-настройка.
 */
class SelfEducationPlugin(
    private val educator: SkillEducator,
    private val installer: SkillInstaller,
    private val store: SkillStore,
    private val chats: ChatRepository,
    private val settingsRepo: SettingsRepository,
    private val profileRepo: LlmProfileRepository,
) : MagicPlugin {
    override val id = "self-education"
    override val title = "Самообучение"
    override val description = "Агент сам создаёт навыки из диалога — вы только подтверждаете."
    override val icon = "✦"

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val skills by store.skills.collectAsState()
        var draft by remember { mutableStateOf<SkillDraft?>(null) }
        var busy by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf<String?>(null) }

        // Черновик из последней сессии.
        fun propose() {
            if (busy) return
            busy = true
            notice = null
            scope.launch {
                val session = chats.sessions().firstOrNull()
                if (session == null || session.messages.isEmpty()) {
                    notice = "Сначала поговорите с агентом — навык рождается из диалога."
                    busy = false
                    return@launch
                }
                val settings = settingsRepo.load()
                val profile = ProfileResolver.resolve(session, settings, profileRepo.load())
                draft = educator.propose(session.messages, profile)
                busy = false
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            PaperText(icon + " " + title, style = LocalPaperTypography.current.title)
            PaperText(
                "После удачного диалога агент предложит превратить подход в навык. " +
                    "Подтвердите — и он будет применять его сам.",
                style = LocalPaperTypography.current.body,
                color = LocalPaperColors.current.secondaryText,
            )
            Spacer(Modifier.height(8.dp))
            PaperAction(onClick = ::propose, enabled = !busy) {
                PaperText(if (busy) "Обдумываю…" else "Предложить навык из последнего диалога")
            }
            notice?.let {
                PaperText(
                    it,
                    style = LocalPaperTypography.current.body,
                    color = LocalPaperColors.current.action,
                )
            }
            draft?.let { current ->
                Spacer(Modifier.height(8.dp))
                DraftEditor(current, onChange = { draft = it }, onSave = {
                    scope.launch {
                        val editable = draft ?: return@launch
                        when (val outcome = installer.installDraft(editable)) {
                            is SkillInstaller.Outcome.Installed -> {
                                notice = "Навык «${editable.name}» сохранён и включён."
                                draft = null
                            }

                            is SkillInstaller.Outcome.Conflict -> notice = outcome.message
                        }
                    }
                }, onDismiss = { draft = null })
            }
            Spacer(Modifier.height(12.dp))
            PaperText("Созданные навыки", style = LocalPaperTypography.current.label)
            val selfMade = skills.filter { it.source == SkillSource.SELF_MADE }
            if (selfMade.isEmpty()) {
                PaperText(
                    "Пока нет навыков, созданных агентом.",
                    style = LocalPaperTypography.current.body,
                    color = LocalPaperColors.current.secondaryText,
                )
            }
            selfMade.forEach { skill ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clip(PaperShapes.panel)
                        .background(LocalPaperColors.current.surface)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        PaperText(skill.name, style = LocalPaperTypography.current.body)
                        PaperText(
                            skill.description,
                            style = LocalPaperTypography.current.label,
                            color = LocalPaperColors.current.secondaryText,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    PaperToggle(
                        checked = skill.enabled,
                        onCheckedChange = { enabled ->
                            scope.launch { store.save(skill.copy(enabled = enabled)) }
                        },
                    )
                    PaperAction(onClick = { scope.launch { store.delete(skill.id) } }) {
                        PaperText("✕")
                    }
                }
            }
        }
    }

    /** Редактор черновика: имя, «когда применять», инструкция. */
    @Composable
    private fun DraftEditor(
        draft: SkillDraft,
        onChange: (SkillDraft) -> Unit,
        onSave: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PaperShapes.panel)
                .background(LocalPaperColors.current.surface)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PaperText("Черновик навыка", style = LocalPaperTypography.current.label)
            draft.note?.let {
                PaperText(
                    it,
                    style = LocalPaperTypography.current.label,
                    color = LocalPaperColors.current.secondaryText,
                )
            }
            PaperInput(
                value = draft.name,
                onValueChange = { onChange(draft.copy(name = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { PaperText("Название") },
                singleLine = true,
            )
            PaperInput(
                value = draft.description,
                onValueChange = { onChange(draft.copy(description = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { PaperText("Когда применять") },
                minLines = 1,
                maxLines = 3,
            )
            PaperInput(
                value = draft.instructions,
                onValueChange = { onChange(draft.copy(instructions = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { PaperText("Инструкция для агента") },
                minLines = 3,
                maxLines = 10,
            )
            Row {
                PaperAction(
                    onClick = onSave,
                    enabled = draft.name.isNotBlank() && draft.instructions.isNotBlank(),
                ) {
                    PaperText("Сохранить и включить")
                }
                PaperAction(onClick = onDismiss) { PaperText("Отменить") }
            }
        }
    }
}
