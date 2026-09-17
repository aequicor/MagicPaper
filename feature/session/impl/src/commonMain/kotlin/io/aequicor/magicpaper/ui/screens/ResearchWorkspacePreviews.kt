package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.ChatState
import io.aequicor.magicpaper.ui.ResearchBrowserPhase
import io.aequicor.magicpaper.ui.ResearchBrowserState
import io.aequicor.magicpaper.ui.components.DefaultChatPresentation
import io.aequicor.magicpaper.ui.components.LocalChatPresentation

@Preview(name = "Browser reading", group = "Research", widthDp = 480, heightDp = 340)
@Preview(name = "Browser reading narrow", group = "Research", widthDp = 360, heightDp = 460)
@Composable
internal fun ResearchBrowserPreview(phase: ResearchBrowserPhase = ResearchBrowserPhase.READY,
    onRead: () -> Unit = {}, onDismiss: () -> Unit = {}, pageOpen: Boolean = phase != ResearchBrowserPhase.OPENING) = PaperTheme {
    PaperSurface(Modifier.fillMaxSize()) {
        ResearchBrowserContent(ResearchBrowserState("question", "source", "What is Code Quality?", "https://example.org/article",
            phase, pageOpen = pageOpen,
            problem = if (phase == ResearchBrowserPhase.FAILED) {
                if (pageOpen) "CAPTCHA или защита сайта" else "Окно браузера закрыто. Откройте источник заново."
            } else null), onRead, onDismiss)
    }
}

@Preview(name = "Model failure", group = "Research", widthDp = 680, heightDp = 360)
@Preview(name = "Model failure narrow", group = "Research", widthDp = 360, heightDp = 460)
@Composable
internal fun ResearchModelFailurePreview(onResume: () -> Unit = {}) {
    PaperTheme { PaperSurface(Modifier.fillMaxSize()) { PaperResearchReading {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            ResearchActivity(listOf(
                CodingStep(CodingStepKind.TOOL, "Прочитано источников: 2; недоступно: 3", tool = "web.read", id = "read"),
                CodingStep(CodingStepKind.ERROR, RESEARCH_MODEL_FAILURE, ok = false, id = "model")),
                busy = false, paused = true, failed = true, onResume = onResume)
        }
    } } }
}

internal fun researchPreviewState(empty: Boolean = false, busy: Boolean = false): ChatState {
    val root = ChatSession("research", "Android-разработка", 1, 1,
        messages = if (empty) emptyList() else listOf(
            ChatMessage("question", ChatRole.USER, "С чего начать Android-разработку?", 1),
            ChatMessage("answer", ChatRole.AGENT,
                "Начните с небольшого приложения на Kotlin и Jetpack Compose — например, списка задач. " +
                "Один рабочий экран поможет связать основы языка с состоянием интерфейса.\n\n" +
                "Если вы уже программировали, можно сразу перейти к Compose; без такого опыта сначала " +
                "разберите типы, функции и null-безопасность в Kotlin. Навигацию и хранение данных добавляйте по мере необходимости.", 2,
                followUps = listOf("Подобрать первый проект под мой опыт", "Сравнить учебные материалы по Kotlin и Compose",
                    "Написать статью: путь от первого экрана до Android-приложения"),
                researchActivity = listOf(CodingStep(CodingStepKind.TOOL, "Найдено источников: 2", tool = "web.search", id = "search-complete")),
                sources = listOf(
                    SearchHit("Kotlin Documentation", "https://kotlinlang.org/docs/home.html", "Официальное руководство по языку Kotlin и корутинам."),
                    SearchHit("Jetpack Compose", "https://developer.android.com/compose", "Документация по современному UI Android."),
                ))),
        pendingRun = if (busy) CodingRunCheckpoint("follow-up", "Как организовать обучение?", responseId = "live-answer") else null,
        questionResources = if (empty) emptyList() else listOf(ResearchResource("compose", "Jetpack Compose", "https://developer.android.com/compose", discovered = true)),
        disabledResourceKeys = if (empty) emptySet() else setOf("url:https://developer.android.com/topic/architecture"),
        resources = if (empty) emptyList() else listOf(
            ResearchResource("architecture", "Guide to app architecture", "https://developer.android.com/topic/architecture", discovered = true,
                snippet = "Рекомендации Android по слоям, состоянию и потоку данных."),
            ResearchResource("plan", "План обучения.pdf", attachment =
                Attachment.fromBytes("План обучения.pdf", "application/pdf", "План".encodeToByteArray()))))
    val architecture = ChatSession("architecture-question", "Архитектура приложения", 3, 3, researchParentId = root.id,
        messages = listOf(ChatMessage("architecture-prompt", ChatRole.USER, "Архитектура приложения", 3)))
    val compose = ChatSession("compose-question", "Jetpack Compose", 4, 4, researchParentId = root.id,
        messages = listOf(ChatMessage("compose-prompt", ChatRole.USER, "Jetpack Compose", 4)))
    val selected = if (busy) root.copy(messages = root.messages + ChatMessage("follow-up", ChatRole.USER,
        "Какие ресурсы помогут составить план на месяц?", 5)) else root
    return ChatState(sessions = if (empty) listOf(selected) else listOf(selected, architecture, compose), current = selected, busy = busy,
        drafts = if (busy) mapOf(root.id to CodingDraft(active = true, steps = listOf(
            CodingStep(CodingStepKind.TOOL, "Проверены выбранные источники", tool = "read", id = "checked"),
            CodingStep(CodingStepKind.TOOL, "Найдено источников: 2", tool = "web.search", id = "searching"),
            CodingStep(CodingStepKind.TOOL, "Чтение и сравнение материалов", tool = "read", running = true, id = "reading")))) else emptyMap())
}

@Preview(name = "Research dialogue", group = "Research", widthDp = 1280, heightDp = 850)
@Preview(name = "Narrow", group = "Research", widthDp = 390, heightDp = 780)
@Preview(name = "Large text", group = "Research", widthDp = 720, heightDp = 1000, fontScale = 2f)
@Composable
internal fun ResearchWorkspacePreview(
    empty: Boolean = false,
    busy: Boolean = false,
    failed: Boolean = false,
    initialSourceScope: ResearchResourceScope = ResearchResourceScope.SHARED,
    unreadableSource: Boolean = false,
    onNewQuestion: () -> Unit = {},
    onPickFiles: (ResearchResourceScope) -> Unit = {},
) {
    var state by remember(empty, busy, unreadableSource) { mutableStateOf(researchPreviewState(empty, busy).let { state ->
        if (unreadableSource) state.copy(sourceReadProblems = mapOf("research" to mapOf(
            "url:https://developer.android.com/topic/architecture" to "CAPTCHA или защита сайта"))) else state
    }) }
    var questionsExpanded by remember { mutableStateOf(true) }
    var sourcesExpanded by remember { mutableStateOf(true) }
    var sourceScope by remember { mutableStateOf(initialSourceScope) }
    var questionsWidth by remember { mutableStateOf(216f) }
    var sourcesWidth by remember { mutableStateOf(272f) }
    fun selectSources(keys: Set<String>, enabled: Boolean) {
        val current = state.current ?: return
        val updated = current.copy(disabledResourceKeys = if (enabled) current.disabledResourceKeys - keys else current.disabledResourceKeys + keys)
        state = state.copy(current = updated, sessions = state.sessions.map { if (it.id == updated.id) updated else it })
    }
    PaperTheme {
        PaperSurface(Modifier.fillMaxSize(), kind = PaperSurfaceKind.CANVAS) {
            CompositionLocalProvider(LocalChatPresentation provides DefaultChatPresentation) {
                ResearchWorkspaceContent(state,
                    questionsExpanded = questionsExpanded,
                    sourcesExpanded = sourcesExpanded,
                    sourceScope = sourceScope,
                    onSourceScopeChange = { sourceScope = it },
                    questionsWidth = questionsWidth,
                    sourcesWidth = sourcesWidth,
                    onQuestionsExpandedChange = { questionsExpanded = it },
                    onSourcesExpandedChange = { sourcesExpanded = it },
                    onQuestionsWidthChange = { questionsWidth = it },
                    onSourcesWidthChange = { sourcesWidth = it },
                    error = if (failed) "Не удалось сохранить изменение. Повторите попытку." else null,
                    onNewQuestion = onNewQuestion, onForkQuestion = {}, onPickFiles = onPickFiles,
                    onResourceEnabled = { key, enabled -> selectSources(setOf(key), enabled) },
                    onResourcesEnabled = ::selectSources,
                    onSelectQuestion = { id -> state = state.copy(current = state.sessions.first { it.id == id }) }) {
                    PaperResearchReading {
                        MessagesList(state.current, state.busy, draft = state.drafts[state.current?.id],
                            activitySources = if (busy) listOf(SearchHit("Kotlin Documentation", "https://kotlinlang.org/docs/home.html"),
                                SearchHit("Jetpack Compose — руководство и примеры", "https://developer.android.com/compose")) else emptyList(),
                            onPause = {}, onResume = {}, modifier = Modifier.fillMaxSize(),
                            onFollowUp = { _, question -> state = state.copy(current = state.current?.let { current ->
                                current.copy(messages = current.messages + ChatMessage("preview-follow-up", ChatRole.USER, question, 5))
                            }) },
                            footer = {
                            Composer(enabled = true, busy = busy, session = state.current,
                                profiles = listOf(LlmProfile("preview", "GPT-5.5", baseUrl = "https://preview.invalid", modelId = "gpt-5.5", effort = EffortSelection.of(ReasoningEffort.HIGH))), activeProfileId = "preview",
                                contextUsage = ContextUsageSnapshot("chat:research", "gpt-5.5", 30_720, 128_000), onSend = { _, _ -> },
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

@Preview(name = "Question sources", group = "Research", widthDp = 1280, heightDp = 850)
@Composable
internal fun ResearchSourcesCollapsedPreview() = ResearchWorkspacePreview(initialSourceScope = ResearchResourceScope.QUESTION)

@Preview(name = "Working", group = "Research", widthDp = 1280, heightDp = 850)
@Composable
internal fun ResearchBusyPreview() = ResearchWorkspacePreview(busy = true)

@Preview(name = "Save error", group = "Research", widthDp = 390, heightDp = 780)
@Composable
internal fun ResearchErrorPreview() = ResearchWorkspacePreview(failed = true)

@Preview(name = "Unreadable source", group = "Research", widthDp = 1280, heightDp = 850)
@Composable
internal fun ResearchUnreadableSourcePreview() = ResearchWorkspacePreview(unreadableSource = true)
