package io.aequicor.magicpaper.plugins.builtin

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
                draft = educator.propose(session.messages, settings)
                busy = false
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(icon + " " + title, style = MaterialTheme.typography.titleMedium)
            Text(
                "После удачного диалога агент предложит превратить подход в навык. " +
                    "Подтвердите — и он будет применять его сам.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = ::propose, enabled = !busy) {
                Text(if (busy) "Обдумываю…" else "Предложить навык из последнего диалога")
            }
            notice?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
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
            Text("Созданные навыки", style = MaterialTheme.typography.titleSmall)
            val selfMade = skills.filter { it.source == SkillSource.SELF_MADE }
            if (selfMade.isEmpty()) {
                Text(
                    "Пока нет навыков, созданных агентом.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            selfMade.forEach { skill ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(skill.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            skill.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = skill.enabled,
                        onCheckedChange = { enabled ->
                            scope.launch { store.save(skill.copy(enabled = enabled)) }
                        },
                    )
                    TextButton(onClick = { scope.launch { store.delete(skill.id) } }) {
                        Text("✕")
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
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surface)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Черновик навыка", style = MaterialTheme.typography.titleSmall)
            draft.note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = draft.name,
                onValueChange = { onChange(draft.copy(name = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Название") },
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.description,
                onValueChange = { onChange(draft.copy(description = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Когда применять") },
                minLines = 1,
                maxLines = 3,
            )
            OutlinedTextField(
                value = draft.instructions,
                onValueChange = { onChange(draft.copy(instructions = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Инструкция для агента") },
                minLines = 3,
                maxLines = 10,
            )
            Row {
                TextButton(
                    onClick = onSave,
                    enabled = draft.name.isNotBlank() && draft.instructions.isNotBlank(),
                ) {
                    Text("Сохранить и включить")
                }
                TextButton(onClick = onDismiss) { Text("Отменить") }
            }
        }
    }
}
