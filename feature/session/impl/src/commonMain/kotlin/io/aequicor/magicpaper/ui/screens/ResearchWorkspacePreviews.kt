package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.ChatState
import io.aequicor.magicpaper.ui.components.DefaultChatPresentation
import io.aequicor.magicpaper.ui.components.LocalChatPresentation

internal fun researchPreviewState(empty: Boolean = false, busy: Boolean = false): ChatState {
    val root = ChatSession("research", "Android-разработка", 1, 1,
        messages = if (empty) emptyList() else listOf(
            ChatMessage("question", ChatRole.USER, "С чего начать Android-разработку?", 1),
            ChatMessage("answer", ChatRole.AGENT, "# Ваш путь в Android-разработку\n\n" +
                "Начните с Kotlin и Jetpack Compose. Затем переходите к архитектуре, данным и тестированию.\n\n" +
                "1. **Освойте Kotlin**\n   Разберитесь с типами, null-безопасностью и корутинами.\n\n" +
                "2. **Создайте первый экран**\n   Соберите небольшой интерфейс в Jetpack Compose и научитесь управлять состоянием.\n\n" +
                "3. **Соберите небольшое приложение**\n   Добавьте навигацию, локальное хранение и тесты.\n\n" +
                "> **Первый проект**\n> Список задач: три экрана, хранение данных, навигация.", 2)),
        resources = if (empty) emptyList() else listOf(
            ResearchResource("kotlin", "Kotlin Documentation", "https://kotlinlang.org/docs/home.html", discovered = true,
                snippet = "Официальное руководство по языку Kotlin и корутинам."),
            ResearchResource("compose", "Jetpack Compose", "https://developer.android.com/compose", discovered = true,
                snippet = "Современный набор инструментов для создания интерфейсов Android."),
            ResearchResource("architecture", "Guide to app architecture", "https://developer.android.com/topic/architecture", discovered = true,
                snippet = "Рекомендации Android по слоям, состоянию и потоку данных."),
            ResearchResource("plan", "План обучения.pdf", attachment =
                Attachment.fromBytes("План обучения.pdf", "application/pdf", "План".encodeToByteArray()))))
    val architecture = ChatSession("architecture-question", "Архитектура приложения", 3, 3, researchParentId = root.id,
        messages = listOf(ChatMessage("architecture-prompt", ChatRole.USER, "Архитектура приложения", 3)))
    val compose = ChatSession("compose-question", "Jetpack Compose", 4, 4, researchParentId = root.id,
        messages = listOf(ChatMessage("compose-prompt", ChatRole.USER, "Jetpack Compose", 4)))
    return ChatState(sessions = if (empty) listOf(root) else listOf(root, architecture, compose), current = root, busy = busy)
}

@Preview(name = "Reading", group = "Research", widthDp = 1280, heightDp = 850)
@Preview(name = "Narrow", group = "Research", widthDp = 390, heightDp = 780)
@Preview(name = "Large text", group = "Research", widthDp = 720, heightDp = 1000, fontScale = 2f)
@Composable
internal fun ResearchWorkspacePreview(
    empty: Boolean = false,
    busy: Boolean = false,
    failed: Boolean = false,
    onNewQuestion: () -> Unit = {},
    onPickFiles: () -> Unit = {},
) {
    val state = researchPreviewState(empty, busy)
    var questionsExpanded by remember { mutableStateOf(true) }
    var sourcesExpanded by remember { mutableStateOf(true) }
    var questionsWidth by remember { mutableStateOf(252f) }
    var sourcesWidth by remember { mutableStateOf(304f) }
    PaperTheme {
        PaperSurface(Modifier.fillMaxSize(), kind = PaperSurfaceKind.CANVAS) {
            CompositionLocalProvider(LocalChatPresentation provides DefaultChatPresentation) {
                ResearchWorkspaceContent(state,
                    questionsExpanded = questionsExpanded,
                    sourcesExpanded = sourcesExpanded,
                    questionsWidth = questionsWidth,
                    sourcesWidth = sourcesWidth,
                    onQuestionsExpandedChange = { questionsExpanded = it },
                    onSourcesExpandedChange = { sourcesExpanded = it },
                    onQuestionsWidthChange = { questionsWidth = it },
                    onSourcesWidthChange = { sourcesWidth = it },
                    error = if (failed) "Не удалось сохранить изменение. Повторите попытку." else null,
                    onNewQuestion = onNewQuestion, onForkQuestion = {}, onPickFiles = onPickFiles) {
                    PaperResearchReading {
                        MessagesList(state.current, state.busy, modifier = Modifier.fillMaxSize(),
                            researchSourceCount = state.notebook?.resources?.size ?: 0, footer = {
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
