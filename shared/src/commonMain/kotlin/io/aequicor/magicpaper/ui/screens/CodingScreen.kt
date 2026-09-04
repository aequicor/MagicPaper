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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.aequicor.magicpaper.domain.CodingStep
import io.aequicor.magicpaper.domain.CodingStepKind
import io.aequicor.magicpaper.domain.RuntimePhase
import io.aequicor.magicpaper.domain.RuntimeStatus
import io.aequicor.magicpaper.ui.CodingUi
import io.aequicor.magicpaper.ui.MagicPaperViewModel
import io.aequicor.magicpaper.ui.components.ChatMarkdown
import io.aequicor.magicpaper.ui.theme.MagicFonts

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
    val listState = rememberLazyListState()
    // Перематываем только если пользователь и так внизу — иначе живая лента
    // не даст прочитать то, что агент сделал выше.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index == info.totalItemsCount - 1
        }
    }
    val lastId = messages.lastOrNull()?.id
    LaunchedEffect(messages.size, lastId, draft.steps.size, atBottom) {
        if (atBottom) {
            listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
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
            if (busy || draft.steps.isNotEmpty() || draft.failedMessage != null) {
                item(key = "draft") { DraftBubble(draft) }
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

/** Бабл записи журнала: лента прогона в хронологическом порядке либо просто текст. */
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
                .widthIn(max = 680.dp)
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
            if (isUser) {
                Text(message.text, style = MaterialTheme.typography.bodyLarge)
            } else if (message.steps.isNotEmpty()) {
                // Лента: текст и действия идут как приходили — в хронологическом порядке.
                message.steps.forEach { step -> CodingStepRow(step, live = false) }
            } else {
                // Совместимость со старыми журналами без ленты.
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
}

/**
 * Строка ленты прогона: текст ответа — как есть, действия — сворачиваемой строкой
 * с раскрывающимся выводом инструмента (что реально пришло в ответ).
 */
@Composable
private fun CodingStepRow(step: CodingStep, live: Boolean) {
    when (step.kind) {
        CodingStepKind.ANSWER -> {
            Spacer(Modifier.height(4.dp))
            ChatMarkdown(text = step.title)
            Spacer(Modifier.height(4.dp))
        }
        CodingStepKind.ERROR -> Text(
            "✕ ${step.title}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 3.dp),
        )
        CodingStepKind.TOOL, CodingStepKind.EXEC -> ToolStepRow(step, live)
    }
}

@Composable
private fun ToolStepRow(step: CodingStep, live: Boolean) {
    var expanded by rememberSaveable(step.callId.ifBlank { step.title }) { mutableStateOf(false) }
    val statusGlyph = when {
        step.running -> "◷"
        !step.ok -> "✕"
        else -> "✔"
    }
    val hasDetail = step.result.isNotBlank()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .then(if (hasDetail) Modifier.clickable { expanded = !expanded } else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                statusGlyph,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    step.running -> MaterialTheme.colorScheme.primary
                    !step.ok -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                step.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                modifier = Modifier.weight(1f),
            )
            if (hasDetail) {
                Text(
                    if (expanded) "▴" else "▾",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (step.running && live && step.result.isBlank()) {
            Text(
                "выполняется…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (expanded && hasDetail) {
            Spacer(Modifier.height(4.dp))
            Text(
                step.result,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = MagicFonts.code,
                ),
                color = if (step.ok) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

/** Живая лента прогона: то же, что в итоге попадёт в журнал, пока agent работает. */
@Composable
private fun DraftBubble(draft: CodingDraft) {
    Column(
        modifier = Modifier
            .widthIn(max = 680.dp)
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
        Spacer(Modifier.height(4.dp))
        if (draft.steps.isEmpty() && draft.active) {
            Text(
                "Жду ответ движка…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        draft.steps.forEach { step -> CodingStepRow(step, live = true) }
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
