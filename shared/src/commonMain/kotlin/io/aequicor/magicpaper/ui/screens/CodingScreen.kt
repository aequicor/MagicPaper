package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.CodingDraft
import io.aequicor.magicpaper.domain.CodingMessage
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingRole
import io.aequicor.magicpaper.domain.RuntimePhase
import io.aequicor.magicpaper.domain.RuntimeStatus
import io.aequicor.magicpaper.ui.CodingUi
import io.aequicor.magicpaper.ui.MagicPaperViewModel

/** Экран «Проекты и код»: агент пи работает в выбранной директории проекта. */
@Composable
fun CodingScreen(vm: MagicPaperViewModel, ui: CodingUi) {
    Column(modifier = Modifier.fillMaxSize()) {
        RuntimeBar(ui.runtime, ui.installing, vm::prepareCodingRuntime, vm::uninstallCodingRuntime)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(modifier = Modifier.weight(1f)) {
            ProjectList(
                projects = ui.projects,
                currentId = ui.current?.id,
                onAdd = vm::addCodingProject,
                onSelect = vm::selectCodingProject,
                onDelete = vm::deleteCodingProject,
                modifier = Modifier.width(240.dp),
            )
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(modifier = Modifier.weight(1f)) {
                if (ui.current == null) {
                    ProjectsEmptyHint()
                } else {
                    CodingChat(
                        project = ui.current,
                        messages = ui.messages,
                        draft = ui.draft,
                        busy = ui.busy,
                        engineReady = ui.runtime.ready,
                        onSend = vm::sendCodingPrompt,
                        onAbort = vm::abortCodingRun,
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeBar(
    runtime: RuntimeStatus,
    installing: Boolean,
    onPrepare: () -> Unit,
    onUninstall: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            runtimeGlyph(runtime),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                buildString {
                    append("Движок: ")
                    append(runtimeLabel(runtime))
                    if (runtime.version.isNotBlank()) append(" · v${runtime.version}")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                runtime.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        if (installing) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 8.dp).height(16.dp).width(16.dp),
                strokeWidth = 2.dp,
            )
        } else if (!runtime.ready) {
            TextButton(onClick = onPrepare, modifier = Modifier.heightIn(min = 40.dp)) {
                Text("Подготовить движок")
            }
        }
        TextButton(onClick = onUninstall, modifier = Modifier.heightIn(min = 40.dp)) {
            Text("Удалить зависимости")
        }
    }
}

private fun runtimeGlyph(runtime: RuntimeStatus): String = when {
    runtime.ready -> "✦"
    runtime.phase == RuntimePhase.ERROR -> "✕"
    runtime.phase == RuntimePhase.UNSUPPORTED -> "✕"
    else -> "◷"
}

private fun runtimeLabel(runtime: RuntimeStatus): String = when (runtime.phase) {
    RuntimePhase.READY -> "готов"
    RuntimePhase.CHECKING -> "проверка"
    RuntimePhase.INSTALLING -> "установка"
    RuntimePhase.ERROR -> "ошибка"
    RuntimePhase.UNSUPPORTED -> "недоступен на этой платформе"
    RuntimePhase.UNKNOWN -> "неизвестно"
}

@Composable
private fun ProjectList(
    projects: List<CodingProject>,
    currentId: String?,
    onAdd: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            "Проекты",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(projects, key = { it.id }) { project ->
                ProjectRow(project, project.id == currentId, onSelect, onDelete)
            }
        }
        TextButton(onClick = onAdd, modifier = Modifier.padding(8.dp)) {
            Text("✦ Новый проект")
        }
    }
}

@Composable
private fun ProjectRow(
    project: CodingProject,
    selected: Boolean,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = { onSelect(project.id) })
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                project.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                project.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        TextButton(onClick = { onDelete(project.id) }) {
            Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProjectsEmptyHint() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("✦", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(
            "Добавьте проект — папку, в которой агент будет читать файлы и выполнять запросы.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CodingChat(
    project: CodingProject,
    messages: List<CodingMessage>,
    draft: CodingDraft,
    busy: Boolean,
    engineReady: Boolean,
    onSend: (String) -> Unit,
    onAbort: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Проект «${project.name}» · ${project.path}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(messages, key = { it.id }) { message -> CodingMessageBubble(message) }
            if (busy || draft.text.isNotEmpty() || draft.activity.isNotEmpty()) {
                item { DraftBubble(draft) }
            }
        }
        CodingComposer(
            enabled = engineReady && !busy,
            busy = busy,
            onSend = onSend,
            onAbort = onAbort,
        )
    }
}

@Composable
private fun CodingMessageBubble(message: CodingMessage) {
    val isUser = message.role == CodingRole.USER
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .clip(
                    androidx.compose.foundation.shape.RoundedCornerShape(
                        topStart = if (isUser) 20.dp else 6.dp,
                        topEnd = if (isUser) 6.dp else 20.dp,
                        bottomStart = 20.dp,
                        bottomEnd = 20.dp,
                    )
                )
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (message.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (message.activity.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Действия агента:",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                message.activity.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DraftBubble(draft: CodingDraft) {
    Column(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (draft.active) {
                CircularProgressIndicator(
                    modifier = Modifier.height(12.dp).width(12.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (draft.active) "Агент работает…" else "Черновик",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (draft.text.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(draft.text, style = MaterialTheme.typography.bodyMedium)
        }
        if (draft.failedMessage != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                draft.failedMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        draft.activity.takeLast(6).forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CodingComposer(
    enabled: Boolean,
    busy: Boolean,
    onSend: (String) -> Unit,
    onAbort: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    fun submit() {
        if (text.isBlank()) return
        onSend(text)
        text = ""
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Поручение агенту в папке проекта…") },
                minLines = 1,
                maxLines = 5,
                shape = MaterialTheme.shapes.large,
            )
            Spacer(Modifier.width(8.dp))
            if (busy) {
                TextButton(onClick = onAbort, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Прервать", fontWeight = FontWeight.Medium)
                }
            } else {
                TextButton(
                    enabled = enabled && text.isNotBlank(),
                    onClick = ::submit,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (enabled) "Отправить" else "Движок не готов")
                }
            }
        }
    }
}
