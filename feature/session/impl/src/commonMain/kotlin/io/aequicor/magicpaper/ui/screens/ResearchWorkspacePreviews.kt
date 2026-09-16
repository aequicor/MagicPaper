package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.ChatState
import io.aequicor.magicpaper.ui.components.DefaultChatPresentation
import io.aequicor.magicpaper.ui.components.LocalChatPresentation

internal fun researchPreviewState(empty: Boolean = false, busy: Boolean = false): ChatState {
    val root = ChatSession("research", "Как устроены городские сады", 1, 1,
        messages = if (empty) emptyList() else listOf(
            ChatMessage("question", ChatRole.USER, "Как городские сады влияют на качество жизни?", 1),
            ChatMessage("answer", ChatRole.AGENT, "## Три направления для исследования\n\n" +
                "Городской сад — это место для отдыха, совместной работы и наблюдения за природой. " +
                "Чтобы оценить его влияние, полезно разделить экологические и социальные эффекты.\n\n" +
                "1. **Микроклимат.** Сравните температуру и доступность тени.\n" +
                "2. **Общение.** Изучите, кто участвует в уходе за садом.\n" +
                "3. **Доступность.** Оцените расстояние от домов и удобство дорожек.\n\n" +
                "### Что проверить дальше\n\nСопоставьте результаты нескольких районов и сезонные различия.", 2)),
        resources = if (empty) emptyList() else listOf(
            ResearchResource("source", "Исследование городской среды", "https://example.org/urban-gardens", discovered = true),
            ResearchResource("notes", "Наблюдения за городскими садами.txt", attachment =
                Attachment.fromBytes("Наблюдения за городскими садами.txt", "text/plain", "Наблюдения".encodeToByteArray()))))
    val question = ChatSession("second", "Как сравнить районы?", 3, 3, researchParentId = root.id,
        messages = listOf(ChatMessage("second-question", ChatRole.USER, "Как сравнить районы?", 3)))
    return ChatState(sessions = if (empty) listOf(root) else listOf(root, question), current = root, busy = busy)
}

@Preview(name = "Reading", group = "Research", widthDp = 1280, heightDp = 850)
@Preview(name = "Narrow", group = "Research", widthDp = 390, heightDp = 780)
@Preview(name = "Large text", group = "Research", widthDp = 720, heightDp = 1000, fontScale = 2f)
@Composable
internal fun ResearchWorkspacePreview(empty: Boolean = false, busy: Boolean = false, failed: Boolean = false) {
    val state = researchPreviewState(empty, busy)
    PaperTheme {
        PaperSurface(Modifier.fillMaxSize(), kind = PaperSurfaceKind.CANVAS) {
            CompositionLocalProvider(LocalChatPresentation provides DefaultChatPresentation) {
                ResearchWorkspaceContent(state, error = if (failed) "Не удалось сохранить изменение. Повторите попытку." else null) {
                    PaperResearchReading {
                        MessagesList(state.current, state.busy, modifier = Modifier.fillMaxSize(), footer = {
                            Composer(enabled = true, busy = busy, session = state.current,
                                profiles = emptyList(), activeProfileId = "", onSend = { _, _ -> },
                                onOpenSwitcher = {}, onPickAttachments = { _, _ -> })
                        })
                    }
                }
            }
        }
    }
}

@Preview(name = "New research", group = "Research", widthDp = 1280, heightDp = 850)
@Preview(name = "New narrow", group = "Research", widthDp = 390, heightDp = 780)
@Composable
internal fun ResearchEmptyPreview() = ResearchWorkspacePreview(empty = true)

@Preview(name = "Working", group = "Research", widthDp = 1280, heightDp = 850)
@Composable
internal fun ResearchBusyPreview() = ResearchWorkspacePreview(busy = true)

@Preview(name = "Save error", group = "Research", widthDp = 390, heightDp = 780)
@Composable
internal fun ResearchErrorPreview() = ResearchWorkspacePreview(failed = true)
