package io.aequicor.magicpaper.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.Attachment
import io.aequicor.magicpaper.domain.ChatMessage
import io.aequicor.magicpaper.domain.ChatRole
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ModelDefaults
import io.aequicor.magicpaper.domain.ProfileResolver
import io.aequicor.magicpaper.ui.MagicPaperViewModel
import io.aequicor.magicpaper.ui.UiState
import io.aequicor.magicpaper.ui.components.ChatMarkdown
import io.aequicor.magicpaper.ui.components.MessageAttachments
import io.aequicor.magicpaper.ui.components.PendingAttachmentsRow
import io.aequicor.magicpaper.ui.components.stickToBottom

/** Экран чата: лента сообщений и поле заклинаний. */
@Composable
fun ChatScreen(vm: MagicPaperViewModel, state: UiState) {
    // Клавиатуру уже учитывает корневой windowInsetsPadding(WindowInsets.safeDrawing) —
    // ime входит в safeDrawing, поэтому отдельный imePadding здесь не нужен.
    Column(modifier = Modifier.fillMaxSize()) {
        MessagesList(state.current, state.busy, modifier = Modifier.weight(1f))
        Composer(
            enabled = !state.busy,
            session = state.current,
            profiles = state.availableLlmProfiles,
            activeProfileId = state.settings.activeLlmProfileId,
            onSend = { text, attachments -> vm.send(text, attachments) },
            onOpenSwitcher = { vm.toggleModelSwitcher(true) },
            onPickAttachments = { already, onPicked -> vm.pickAttachments(already, onPicked) },
        )
    }
}

@Composable
private fun MessagesList(session: ChatSession?, busy: Boolean, modifier: Modifier = Modifier) {
    val messages = session?.messages.orEmpty()
    val listState = rememberLazyListState()
    // Держим конец ленты (открыли чат — видно последнее сообщение; ответ агента
    // дорастает — видно его конец, а не начало). Вверх открутили — не мешаем.
    stickToBottom(listState, session?.id)
    Box(modifier = Modifier.fillMaxWidth().then(modifier)) {
        if (messages.isEmpty()) {
            EmptyHint()
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }) { MessageBubble(it) }
            }
        }
        AnimatedVisibility(
            visible = busy,
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Row(
                modifier = Modifier.padding(start = 20.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Чары плетутся…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyHint() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("✦", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(
            "Свиток пуст. Задайте вопрос — и бумага ответит.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatRole.USER
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
                // На узких экранах бабл не должна занимать всю ширину —
                // 100% не даёт читаемой строки.
                .widthIn(max = 560.dp)
                .clip(
                    // «хвост» бабла со стороны автора: верхний угол у его края — почти острый.
                    RoundedCornerShape(
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
                // Пользователь пишет обычный текст — без разметки.
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // Прикреплённые файлы: миниатюры изображений, файлы чипами.
                MessageAttachments(message.attachments)
            } else {
                // Ответ агента рендерим как markdown: заголовки, списки,
                // блоки кода с подсветкой синтаксиса и кнопкой копирования.
                ChatMarkdown(message.text)
            }
            if (message.sources.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Источники:",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                message.sources.forEach { hit ->
                    Text(
                        text = "• ${hit.title} — ${hit.url}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** Чип текущей модели в композиции: тап открывает переключатель источника. */
@Composable
private fun ModelChip(
    session: ChatSession?,
    profiles: List<LlmProfile>,
    activeProfileId: String,
    onClick: () -> Unit,
) {
    val resolved = ProfileResolver.resolve(session, io.aequicor.magicpaper.domain.AppSettings(activeLlmProfileId = activeProfileId), profiles)
    val overridden = session?.llmProfileId != null
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        if (resolved == null) {
            Text(
                "✦ Источник не подключён",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            // Подпись с учётом словаря модели: при подмене уровня видно «х-выс→выс».
            val effortGlyph = resolved.effortLabel(
                ModelDefaults.capability(resolved),
                resolved.modelId,
            )
            Text(
                "${if (overridden) "◌ " else ""}✦ ${resolved.shortLabel} · $effortGlyph ▾",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Composer(
    enabled: Boolean,
    session: ChatSession?,
    profiles: List<LlmProfile>,
    activeProfileId: String,
    onSend: (String, List<Attachment>) -> Unit,
    onOpenSwitcher: () -> Unit,
    onPickAttachments: (Int, (List<Attachment>) -> Unit) -> Unit,
) {
    // Черновик переживает поворот экрана и потерю фокуса окна.
    var text by rememberSaveable { mutableStateOf("") }
    // Прикреплённые файлы живут до отправки; байты в rememberSaveable не сунуть.
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    fun submit() {
        if (text.isBlank() && attachments.isEmpty()) return
        onSend(text, attachments)
        text = ""
        attachments = emptyList()
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // Ряд с чипом модели: отдельная кнопка чата для переключения источника.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModelChip(session, profiles, activeProfileId, onOpenSwitcher)
        }
        PendingAttachmentsRow(
            attachments = attachments,
            onRemove = { target -> attachments = attachments.filterNot { it.id == target.id } },
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                enabled = enabled,
                onClick = { onPickAttachments(attachments.size) { attachments = attachments + it } },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("📎", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(4.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Начертать заклинание…") },
                minLines = 1,
                maxLines = 5,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                enabled = enabled && (text.isNotBlank() || attachments.isNotEmpty()),
                onClick = ::submit,
                // M3: зона касания не меньше 48dp.
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Отправить")
            }
        }
    }
}
