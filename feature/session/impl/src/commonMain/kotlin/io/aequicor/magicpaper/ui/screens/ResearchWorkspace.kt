package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.ui.ChatState
import io.aequicor.magicpaper.ui.DefaultChatComponent
import io.aequicor.magicpaper.ui.window.LocalWindowToolbarHeight
import kotlinx.coroutines.launch

@Composable
internal fun ResearchWorkspace(vm: DefaultChatComponent, state: ChatState, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember(state.notebook?.id) { mutableStateOf<String?>(null) }
    fun edit(action: suspend () -> Result<Unit>) {
        if (saving) return
        saving = true
        scope.launch {
            try { error = action().exceptionOrNull()?.message }
            finally { saving = false }
        }
    }
    fun questionId(): String {
        if (vm.state.value.current == null) vm.newSession()
        return checkNotNull(vm.state.value.current).id
    }
    val notebookId = state.notebook?.id
    val presentation = vm.workspacePresentation.state(notebookId)
    ResearchWorkspaceContent(
        state = state,
        questionsExpanded = presentation.questionsExpanded, sourcesExpanded = presentation.sourcesExpanded,
        sharedSourcesExpanded = presentation.sharedSourcesExpanded, questionSourcesExpanded = presentation.questionSourcesExpanded,
        onSourceGroupExpandedChange = { target, expanded -> vm.workspacePresentation.update(notebookId) {
            if (target == ResearchResourceScope.SHARED) it.copy(sharedSourcesExpanded = expanded)
            else it.copy(questionSourcesExpanded = expanded)
        } },
        questionsWidth = presentation.questionsWidth, sourcesWidth = presentation.sourcesWidth,
        onQuestionsExpandedChange = { value -> vm.workspacePresentation.update(notebookId) { it.copy(questionsExpanded = value) } },
        onSourcesExpandedChange = { value -> vm.workspacePresentation.update(notebookId) { it.copy(sourcesExpanded = value) } },
        onQuestionsWidthChange = { value -> vm.workspacePresentation.update(notebookId) { it.copy(questionsWidth = value) } },
        onSourcesWidthChange = { value -> vm.workspacePresentation.update(notebookId) { it.copy(sourcesWidth = value) } },
        saving = saving, error = error,
        loadSourceIcon = { url -> vm.sourceIcons?.load(url) },
        onNewQuestion = { if (state.notebook == null) vm.newSession() else edit { vm.newQuestion() } },
        onSelectQuestion = { id -> edit { vm.selectQuestion(id) } },
        onForkQuestion = { state.current?.id?.let { id -> edit { vm.forkSession(id).map {} } } },
        onAddWebsite = { url, target -> vm.addWebsite(questionId(), url, target) },
        onSearchResources = vm::searchResources,
        onAddSearchResult = { hit, target -> vm.addSearchResult(questionId(), hit, target) },
        onPickFiles = { target ->
            // A native picker can outlive selection changes. Its result belongs to this question.
            val id = questionId()
            vm.pickAttachments(0) { files -> edit { vm.addResources(id, files, target) } }
        },
        onRemoveResource = { id, target -> state.current?.id?.let { question -> edit { vm.removeResource(question, id, target) } } },
        onResourceEnabled = { key, enabled -> state.current?.id?.let { id -> edit { vm.setResourceEnabled(id, key, enabled) } } },
        onResourcesEnabled = { keys, enabled -> state.current?.id?.let { id -> edit { vm.setResourcesEnabled(id, keys, enabled) } } },
        onShareResource = { resourceId -> state.current?.id?.let { id -> edit { vm.shareResource(id, resourceId) } } },
        content = content,
    )
}

@Composable
internal fun ResearchWorkspaceContent(
    state: ChatState,
    questionsExpanded: Boolean = true,
    sourcesExpanded: Boolean = true,
    sharedSourcesExpanded: Boolean = true,
    questionSourcesExpanded: Boolean = true,
    onSourceGroupExpandedChange: (ResearchResourceScope, Boolean) -> Unit = { _, _ -> },
    questionsWidth: Float = 216f,
    sourcesWidth: Float = 272f,
    onQuestionsExpandedChange: (Boolean) -> Unit = {},
    onSourcesExpandedChange: (Boolean) -> Unit = {},
    onQuestionsWidthChange: (Float) -> Unit = {},
    onSourcesWidthChange: (Float) -> Unit = {},
    saving: Boolean = false,
    error: String? = null,
    onNewQuestion: () -> Unit = {},
    onSelectQuestion: (String) -> Unit = {},
    onForkQuestion: (() -> Unit)? = null,
    onAddWebsite: suspend (String, ResearchResourceScope) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    onSearchResources: suspend (String) -> Result<List<SearchHit>> = { Result.success(emptyList()) },
    onAddSearchResult: suspend (SearchHit, ResearchResourceScope) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    onPickFiles: (ResearchResourceScope) -> Unit = {},
    onRemoveResource: (String, ResearchResourceScope) -> Unit = { _, _ -> },
    onResourceEnabled: (String, Boolean) -> Unit = { _, _ -> },
    onResourcesEnabled: (Set<String>, Boolean) -> Unit = { _, _ -> },
    onShareResource: (String) -> Unit = {},
    loadSourceIcon: suspend (String) -> ImageBitmap? = { null },
    content: @Composable () -> Unit,
) {
    var modalPanel by remember(state.notebook?.id) { mutableStateOf<String?>(null) }
    val inset = LocalWindowToolbarHeight.current ?: 56.dp
    val sources: @Composable ColumnScope.(onCollapse: () -> Unit) -> Unit = { collapse ->
        key(state.current?.id) {
            ResearchSourcesPane(state, saving, onAddWebsite, onSearchResources, onAddSearchResult,
                onPickFiles, onRemoveResource, onResourceEnabled, onShareResource, collapse,
                sharedSourcesExpanded, questionSourcesExpanded, onSourceGroupExpandedChange, loadSourceIcon, onResourcesEnabled)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = inset + PaperTitleBarLaneGap)
        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
        // Preserve a useful reading measure even after resizing either side panel.
        val wide = maxWidth >= 1000.dp
        val availableWidth = maxWidth.value
        val questionWidth = questionsWidth.coerceIn(180f, maxOf(180f, minOf(320f, availableWidth * .25f)))
        val sourceWidth = sourcesWidth.coerceIn(220f, maxOf(220f, minOf(380f, availableWidth * .28f)))
        Row(Modifier.fillMaxSize()) {
            if (wide && questionsExpanded) {
                PaperResearchPane(Modifier.width(questionWidth.dp).fillMaxHeight()) {
                    ResearchQuestionsPane(state, saving, onNewQuestion, onSelectQuestion, { onQuestionsExpandedChange(false) })
                }
                PaperResearchResizeHandle("Изменить ширину панели вопросов", questionWidth, 180f..maxOf(180f, minOf(320f, availableWidth * .25f)), onQuestionsWidthChange)
            }
            PaperResearchPane(Modifier.weight(1f).fillMaxHeight()) {
                ResearchChatHeader(state, saving,
                    showQuestions = !wide || !questionsExpanded, showSources = !wide || !sourcesExpanded,
                    onShowQuestions = { if (wide) onQuestionsExpandedChange(true) else modalPanel = QUESTIONS_PANEL },
                    onShowSources = { if (wide) onSourcesExpandedChange(true) else modalPanel = SOURCES_PANEL },
                    onNewQuestion = onNewQuestion, onForkQuestion = onForkQuestion)
                PaperDivider()
                error?.let { PaperText(it, Modifier.fillMaxWidth().padding(12.dp), role = PaperTextRole.LABEL,
                    color = LocalPaperColors.current.error) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    CompositionLocalProvider(LocalWindowToolbarHeight provides 0.dp, content = content)
                }
            }
            if (wide && sourcesExpanded) {
                PaperResearchResizeHandle("Изменить ширину панели источников", sourceWidth, 220f..maxOf(220f, minOf(380f, availableWidth * .28f)),
                    onSourcesWidthChange, reverseDirection = true)
                PaperResearchPane(Modifier.width(sourceWidth.dp).fillMaxHeight()) { sources { onSourcesExpandedChange(false) } }
            }
        }
    }
    modalPanel?.let { panel ->
        PaperWideDialog(onDismissRequest = { modalPanel = null },
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().fillMaxHeight(.88f)) {
            if (panel == QUESTIONS_PANEL) ResearchQuestionsPane(state, saving,
                { onNewQuestion(); modalPanel = null }, { onSelectQuestion(it); modalPanel = null }, { modalPanel = null })
            else sources { modalPanel = null }
        }
    }
}

@Composable
private fun ResearchChatHeader(state: ChatState, saving: Boolean, showQuestions: Boolean, showSources: Boolean,
    onShowQuestions: () -> Unit, onShowSources: () -> Unit, onNewQuestion: () -> Unit, onForkQuestion: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showQuestions) ResearchPanelToggle("Развернуть вопросы", "›", onShowQuestions)
        PaperText(state.current.researchQuestionTitle(),
            Modifier.weight(1f).semantics { heading() }, role = PaperTextRole.CHROME, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box {
            PaperIconButton("Действия исследования", { menu = true }) { PaperText("⋯", role = PaperTextRole.CHROME) }
            PaperMenuHost(menu, { menu = false }) {
                PaperMenuAction("Новый вопрос", { menu = false; onNewQuestion() }, enabled = !saving)
                if (onForkQuestion != null) PaperMenuAction("Создать ветку", { menu = false; onForkQuestion() }, enabled = !saving && state.current != null)
            }
        }
        if (showSources) ResearchPanelToggle("Развернуть источники", "‹", onShowSources)
    }
}

@Composable
private fun ColumnScope.ResearchQuestionsPane(state: ChatState, saving: Boolean, onNewQuestion: () -> Unit,
    onSelectQuestion: (String) -> Unit, onCollapse: () -> Unit) {
    ResearchPaneHeader("Вопросы", "Скрыть вопросы", "‹", onCollapse)
    PaperDivider()
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        itemsIndexed(state.questions, key = { _, question -> "question:${question.id}" }) { index, question ->
            PaperResearchQuestionRow(index + 1, question.researchQuestionTitle(), state.current?.id == question.id,
                { onSelectQuestion(question.id) }, enabled = !saving)
        }
    }
    PaperButton("+  Новый вопрос", onNewQuestion, Modifier.fillMaxWidth().padding(12.dp),
        enabled = !saving, kind = PaperButtonKind.SECONDARY, accessibilityLabel = "Новый вопрос")
}

@Composable
private fun ColumnScope.ResearchSourcesPane(state: ChatState, saving: Boolean,
    onAddWebsite: suspend (String, ResearchResourceScope) -> Result<Unit>,
    onSearchResources: suspend (String) -> Result<List<SearchHit>>,
    onAddSearchResult: suspend (SearchHit, ResearchResourceScope) -> Result<Unit>,
    onPickFiles: (ResearchResourceScope) -> Unit,
    onRemoveResource: (String, ResearchResourceScope) -> Unit,
    onResourceEnabled: (String, Boolean) -> Unit,
    onShareResource: (String) -> Unit, onCollapse: () -> Unit,
    sharedExpanded: Boolean, questionExpanded: Boolean,
    onGroupExpandedChange: (ResearchResourceScope, Boolean) -> Unit,
    loadSourceIcon: suspend (String) -> ImageBitmap?,
    onResourcesEnabled: (Set<String>, Boolean) -> Unit) {
    var searchOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    val shared = state.notebook?.resources.orEmpty()
    val local = state.current?.questionResources.orEmpty().filterNot { resource -> shared.any { it.key == resource.key } ||
        resource.discovered && resource.url in state.notebook?.excludedResourceUrls.orEmpty() }
    val disabled = state.current?.disabledResourceKeys.orEmpty()
    ResearchPaneHeader("Источники", "Скрыть источники", "›", onCollapse)
    PaperDivider()
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
        for ((target, resources) in listOf(ResearchResourceScope.SHARED to shared, ResearchResourceScope.QUESTION to local)) {
            val expanded = if (target == ResearchResourceScope.SHARED) sharedExpanded else questionExpanded
            item(key = "group:$target") {
                PaperResearchSourceGroupHeader(target.label, expanded, { onGroupExpandedChange(target, !expanded) },
                    Modifier.padding(top = if (target == ResearchResourceScope.QUESTION) 12.dp else 0.dp),
                    selectedCount = resources.count { it.key !in disabled }, totalCount = resources.size,
                    onSelectionChange = { onResourcesEnabled(resources.map { it.key }.toSet(), it) },
                    enabled = !saving && state.current != null) {
                    PaperIconButton("Добавить файлы: ${target.label}", { onPickFiles(target) }, enabled = !saving) {
                        PaperText("+", role = PaperTextRole.TITLE, color = LocalPaperColors.current.action)
                    }
                }
            }
            if (expanded) items(resources, key = { "resource:$target:${it.id}" }) { resource ->
                var menu by remember(resource.id) { mutableStateOf(false) }
                val source = remember(resource.title, resource.url, resource.attachment?.name, resource.attachment?.mimeType) { resource.presentation() }
                val icon by produceState<ImageBitmap?>(null, source.iconUrl) {
                    value = null
                    source.iconUrl?.let { value = loadSourceIcon(it) }
                }
                PaperResearchSourceRow(source.title, resource.key !in disabled, { onResourceEnabled(resource.key, it) },
                    enabled = !saving, keepActionsVisible = menu,
                    detail = source.detail, file = source.file, icon = icon,
                    readProblem = state.sourceReadProblems[state.current?.id]?.get(resource.key)) {
                    Box {
                        PaperIconButton("Действия с источником ${resource.title}", { menu = true }) { PaperText("⋯", role = PaperTextRole.CHROME) }
                        PaperMenuHost(menu, { menu = false }) {
                            if (resource.url.isNotEmpty()) PaperMenuAction("Открыть", {
                                menu = false
                                try { uriHandler.openUri(resource.url) }
                                catch (failure: Exception) {
                                    AppLog.error("chat", "source.open.failed", failure, mapOf("resourceId" to resource.id))
                                    error = "Не удалось открыть источник. Повторите попытку."
                                }
                            })
                            if (target == ResearchResourceScope.QUESTION) PaperMenuAction("Сделать общим", {
                                menu = false; onShareResource(resource.id)
                            }, enabled = !saving)
                            PaperMenuAction(if (target == ResearchResourceScope.SHARED) "Убрать из общих" else "Убрать из вопроса", {
                                menu = false; onRemoveResource(resource.id, target)
                            }, enabled = !saving, destructive = true)
                        }
                    }
                }
            }
        }
    }
    error?.let { PaperText(it, Modifier.padding(12.dp), role = PaperTextRole.LABEL, color = LocalPaperColors.current.error) }
    PaperButton("Найти ещё", { searchOpen = true }, Modifier.fillMaxWidth().padding(12.dp), kind = PaperButtonKind.SECONDARY)
    if (searchOpen) ResearchSourceSearchDialog(shared, local, onSearchResources, onAddSearchResult, onAddWebsite, { searchOpen = false })
}

@Composable
private fun ResearchPaneHeader(title: String, collapseLabel: String, collapseGlyph: String, onCollapse: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PaperText(title, Modifier.weight(1f), role = PaperTextRole.CHROME)
        ResearchPanelToggle(collapseLabel, collapseGlyph, onCollapse)
    }
}

@Composable
private fun ResearchPanelToggle(label: String, glyph: String, onClick: () -> Unit) {
    PaperTooltip(label) { PaperIconButton(label, onClick) {
        PaperText(glyph, role = PaperTextRole.CHROME, color = LocalPaperColors.current.action)
    } }
}

internal val ResearchResourceScope.label: String get() = if (this == ResearchResourceScope.SHARED) "Общие для чата" else "Только этот вопрос"
private fun ChatSession?.researchQuestionTitle(): String =
    this?.messages?.firstOrNull { it.role == ChatRole.USER }?.text?.trim()?.ifBlank { null } ?: "Новый вопрос"
private const val QUESTIONS_PANEL = "questions"
private const val SOURCES_PANEL = "sources"
