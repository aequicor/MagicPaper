package io.aequicor.magicpaper.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.aequicor.magicpaper.ui.components.LocalChatPresentation
import io.aequicor.magicpaper.ui.components.DefaultChatPresentation
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.magicpaper.designsystem.LocalPaperColors
import io.aequicor.magicpaper.domain.ChatMessage
import io.aequicor.magicpaper.domain.ChatRole
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.ui.components.CodingComposerDraft
import io.aequicor.magicpaper.designsystem.PaperTheme

@Preview(name = "Running", group = "Session input", widthDp = 1000, heightDp = 260)
@Preview(name = "Running narrow", group = "Session input", widthDp = 390, heightDp = 260)
@Composable
internal fun RunningSessionComposerPreview() {
    val draft = remember { CodingComposerDraft().apply { text.value = "Уточнение к текущей задаче" } }
    PaperTheme {
        CodingComposer(state = draft, enabled = true, busy = true,
            onSend = { _, _ -> }, onClarify = { _, _ -> }, onAbort = {}, onPickAttachments = { _, _ -> })
    }
}

@Preview(name = "Chat running narrow", group = "Session input", widthDp = 390, heightDp = 240)
@Preview(name = "Chat running", group = "Session input", widthDp = 1000, heightDp = 240)
@Composable
internal fun RunningChatComposerPreview() {
    PaperTheme {
        Composer(enabled = true, busy = true, session = null, profiles = emptyList(), activeProfileId = "",
            onSend = { _, _ -> }, onOpenSwitcher = {}, onPickAttachments = { _, _ -> })
    }
}

internal fun chatTranscriptPreviewSession() = ChatSession("preview", "Чат", 0, 0, listOf(
    ChatMessage("request", ChatRole.USER, "Помоги составить план работы на неделю.", 0),
    ChatMessage("answer", ChatRole.AGENT,
        "Начнём с главного:\n\n1. Определим задачи и сроки.\n2. Оставим время для проверки.\n\n" +
            "**План** можно уточнять по ходу работы. С чего начнём?", 1),
    ChatMessage("followup", ChatRole.USER, "Сначала разберём задачи на понедельник.", 2),
))

@Preview(name = "Chat transcript", group = "Session transcript", widthDp = 1000, heightDp = 700)
@Preview(name = "Chat transcript narrow", group = "Session transcript", widthDp = 390, heightDp = 700)
@Preview(name = "Chat transcript large text", group = "Session transcript", widthDp = 720, heightDp = 900, fontScale = 2f)
@Composable
internal fun ChatTranscriptPreview(session: ChatSession? = chatTranscriptPreviewSession(), busy: Boolean = false) {
    PaperTheme {
        CompositionLocalProvider(LocalChatPresentation provides DefaultChatPresentation) {
            Box(Modifier.fillMaxSize().background(LocalPaperColors.current.canvas)) {
                MessagesList(session, busy, modifier = Modifier.fillMaxSize(), footer = {
                    Composer(enabled = true, busy = busy, session = session,
                        profiles = listOf(LlmProfile("preview", "Модель сессии", baseUrl = "https://example.invalid/v1", modelId = "preview-model")), activeProfileId = "preview",
                        onSend = { _, _ -> }, onOpenSwitcher = {}, onPickAttachments = { _, _ -> })
                })
            }
        }
    }
}

@Preview(name = "Chat empty", group = "Session transcript", widthDp = 390, heightDp = 700)
@Composable
internal fun EmptyChatTranscriptPreview() = ChatTranscriptPreview(session = null)

@Preview(name = "Chat busy", group = "Session transcript", widthDp = 390, heightDp = 700)
@Composable
internal fun BusyChatTranscriptPreview() = ChatTranscriptPreview(busy = true)
