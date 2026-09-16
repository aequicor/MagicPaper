package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
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
    fun rootId(): String {
        if (vm.state.value.notebook == null) vm.newSession()
        return checkNotNull(vm.state.value.notebook).id
    }
    val notebookId = state.notebook?.id
    val presentation = vm.workspacePresentation.state(notebookId)
    ResearchWorkspaceContent(
        state = state,
        questionsExpanded = presentation.questionsExpanded,
        sourcesExpanded = presentation.sourcesExpanded,
        questionsWidth = presentation.questionsWidth,
        sourcesWidth = presentation.sourcesWidth,
        onQuestionsExpandedChange = { value ->
            vm.workspacePresentation.update(notebookId) { it.copy(questionsExpanded = value) }
        },
        onSourcesExpandedChange = { value ->
            vm.workspacePresentation.update(notebookId) { it.copy(sourcesExpanded = value) }
        },
        onQuestionsWidthChange = { value ->
            vm.workspacePresentation.update(notebookId) { it.copy(questionsWidth = value) }
        },
        onSourcesWidthChange = { value ->
            vm.workspacePresentation.update(notebookId) { it.copy(sourcesWidth = value) }
        },
        saving = saving,
        error = error,
        onNewQuestion = { if (state.notebook == null) vm.newSession() else edit { vm.newQuestion() } },
        onSelectQuestion = { id -> edit { vm.selectQuestion(id) } },
        onForkQuestion = {
            state.current?.id?.let { sessionId -> edit { vm.forkSession(sessionId).map {} } }
        },
        onAddWebsite = { url -> vm.addWebsite(rootId(), url) },
        onSearchResources = vm::searchResources,
        onAddSearchResult = { hit -> vm.addSearchResult(rootId(), hit) },
        onPickFiles = {
            val id = rootId()
            vm.pickAttachments(0) { files -> edit { vm.addResources(id, files) } }
        },
        onRemoveResource = { id -> state.notebook?.id?.let { chatId -> edit { vm.removeResource(chatId, id) } } },
        content = content,
    )
}

@Composable
internal fun ResearchWorkspaceContent(
    state: ChatState,
    questionsExpanded: Boolean = true,
    sourcesExpanded: Boolean = true,
    questionsWidth: Float = 252f,
    sourcesWidth: Float = 304f,
    onQuestionsExpandedChange: (Boolean) -> Unit = {},
    onSourcesExpandedChange: (Boolean) -> Unit = {},
    onQuestionsWidthChange: (Float) -> Unit = {},
    onSourcesWidthChange: (Float) -> Unit = {},
    saving: Boolean = false,
    error: String? = null,
    onNewQuestion: () -> Unit = {},
    onSelectQuestion: (String) -> Unit = {},
    onForkQuestion: (() -> Unit)? = null,
    onAddWebsite: suspend (String) -> Result<Unit> = { Result.success(Unit) },
    onSearchResources: suspend (String) -> Result<List<SearchHit>> = { Result.success(emptyList()) },
    onAddSearchResult: suspend (SearchHit) -> Result<Unit> = { Result.success(Unit) },
    onPickFiles: () -> Unit = {},
    onRemoveResource: (String) -> Unit = {},
    content: @Composable () -> Unit,
) {
    var modalPanel by remember(state.notebook?.id) { mutableStateOf<String?>(null) }
    var sourceLinkEditorVisible by remember(state.notebook?.id) { mutableStateOf(false) }
    val inset = LocalWindowToolbarHeight.current ?: 56.dp

    BoxWithConstraints(
        Modifier.fillMaxSize().padding(top = inset + PaperTitleBarLaneGap)
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
    ) {
        val threeColumns = maxWidth >= 1180.dp
        Row(Modifier.fillMaxSize()) {
            if (threeColumns) {
                if (questionsExpanded) {
                    val width = questionsWidth.coerceIn(180f, 420f)
                    PaperResearchPane(Modifier.width(width.dp).fillMaxHeight()) {
                        ResearchQuestionsPane(
                            state = state,
                            saving = saving,
                            onNewQuestion = onNewQuestion,
                            onSelectQuestion = onSelectQuestion,
                            onCollapse = { onQuestionsExpandedChange(false) },
                        )
                    }
                    PaperResearchResizeHandle("Изменить ширину панели вопросов", width, 180f..420f,
                        onQuestionsWidthChange)
                } else {
                    PaperResearchRail(
                        title = "Вопросы",
                        count = state.questions.size,
                        expandLabel = "Развернуть вопросы",
                        expandGlyph = "›",
                        onExpand = { onQuestionsExpandedChange(true) },
                        modifier = Modifier.width(56.dp).fillMaxHeight(),
                    ) {
                        PaperResearchRailAction(
                            label = "Новый вопрос",
                            glyph = "+",
                            onClick = onNewQuestion,
                            enabled = !saving,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                }
            }

            PaperResearchPane(Modifier.weight(1f).fillMaxHeight()) {
                ResearchChatHeader(
                    state = state,
                    saving = saving,
                    wide = threeColumns,
                    onNewQuestion = onNewQuestion,
                    onShowQuestions = { if (threeColumns) onQuestionsExpandedChange(true) else modalPanel = QUESTIONS_PANEL },
                    onShowSources = { if (threeColumns) onSourcesExpandedChange(true) else modalPanel = SOURCES_PANEL },
                    onForkQuestion = onForkQuestion,
                )
                PaperDivider()
                error?.let {
                    PaperText(
                        it,
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        role = PaperTextRole.LABEL,
                        color = LocalPaperColors.current.error,
                    )
                    PaperDivider()
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    CompositionLocalProvider(LocalWindowToolbarHeight provides 0.dp, content = content)
                }
            }

            if (threeColumns) {
                if (sourcesExpanded) {
                    val width = sourcesWidth.coerceIn(220f, 480f)
                    PaperResearchResizeHandle("Изменить ширину панели источников", width, 220f..480f,
                        onSourcesWidthChange, reverseDirection = true)
                    PaperResearchPane(Modifier.width(width.dp).fillMaxHeight()) {
                        ResearchSourcesPane(
                            state = state,
                            saving = saving,
                            showLinkField = sourceLinkEditorVisible,
                            onShowLinkFieldChange = { sourceLinkEditorVisible = it },
                            onAddWebsite = onAddWebsite,
                            onSearchResources = onSearchResources,
                            onAddSearchResult = onAddSearchResult,
                            onPickFiles = onPickFiles,
                            onRemoveResource = onRemoveResource,
                            onCollapse = { onSourcesExpandedChange(false) },
                        )
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                    PaperResearchRail(
                        title = "Источники",
                        count = state.notebook?.resources?.size ?: 0,
                        expandLabel = "Развернуть источники",
                        expandGlyph = "‹",
                        onExpand = { onSourcesExpandedChange(true) },
                        modifier = Modifier.width(56.dp).fillMaxHeight(),
                    ) {
                        PaperResearchRailAction(
                            label = "Добавить ссылку",
                            glyph = "URL",
                            onClick = {
                                sourceLinkEditorVisible = true
                                onSourcesExpandedChange(true)
                            },
                            enabled = !saving,
                        )
                        PaperResearchRailAction(
                            label = "Добавить файлы",
                            glyph = "▤",
                            onClick = onPickFiles,
                            enabled = !saving,
                        )
                    }
                }
            }
        }
    }

    modalPanel?.let { selectedPanel ->
        PaperWideDialog(
            onDismissRequest = { modalPanel = null },
            modifier = Modifier.widthIn(max = if (selectedPanel == SOURCES_PANEL) 680.dp else 520.dp)
                .fillMaxWidth().fillMaxHeight(.88f),
        ) {
            if (selectedPanel == QUESTIONS_PANEL) {
                ResearchQuestionsPane(
                    state = state,
                    saving = saving,
                    onNewQuestion = { onNewQuestion(); modalPanel = null },
                    onSelectQuestion = { onSelectQuestion(it); modalPanel = null },
                    onCollapse = { modalPanel = null },
                    closeGlyph = "×",
                )
            } else {
                ResearchSourcesPane(
                    state = state,
                    saving = saving,
                    showLinkField = sourceLinkEditorVisible,
                    onShowLinkFieldChange = { sourceLinkEditorVisible = it },
                    onAddWebsite = onAddWebsite,
                    onSearchResources = onSearchResources,
                    onAddSearchResult = onAddSearchResult,
                    onPickFiles = onPickFiles,
                    onRemoveResource = onRemoveResource,
                    onCollapse = { modalPanel = null },
                    closeGlyph = "×",
                )
            }
        }
    }
}

@Composable
private fun ResearchChatHeader(
    state: ChatState,
    saving: Boolean,
    wide: Boolean,
    onNewQuestion: () -> Unit,
    onShowQuestions: () -> Unit,
    onShowSources: () -> Unit,
    onForkQuestion: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val roomy = maxWidth >= 620.dp
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    PaperText(
                        "ИССЛЕДОВАНИЕ",
                        role = PaperTextRole.CHROME,
                        color = LocalPaperColors.current.secondaryText,
                    )
                    PaperText(
                        state.current?.title?.ifBlank { "Новая глава" } ?: "Новая глава",
                        role = PaperTextRole.HEADLINE,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                if (roomy && onForkQuestion != null) {
                    PaperButton(
                        "Создать ветку",
                        onForkQuestion,
                        kind = PaperButtonKind.QUIET,
                        enabled = !saving && state.current != null,
                    )
                }
                Box {
                    PaperIconButton("Действия чата", { menuOpen = true }) {
                        PaperText("⋯", role = PaperTextRole.TITLE, color = LocalPaperColors.current.action)
                    }
                    PaperMenuHost(menuOpen, { menuOpen = false }) {
                        if (!roomy && onForkQuestion != null) PaperMenuAction("Создать ветку", {
                            menuOpen = false
                            onForkQuestion()
                        }, enabled = !saving && state.current != null)
                        PaperMenuAction("Новый вопрос", { menuOpen = false; onNewQuestion() }, enabled = !saving)
                        PaperMenuAction("Вопросы (${state.questions.size})", { menuOpen = false; onShowQuestions() })
                        PaperMenuAction("Источники (${state.notebook?.resources?.size ?: 0})", { menuOpen = false; onShowSources() })
                    }
                }
            }
            if (!wide) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PaperButton("+  Новый вопрос", onNewQuestion, enabled = !saving,
                        accessibilityLabel = "Новый вопрос")
                    PaperButton(
                        "Вопросы (${state.questions.size})",
                        onShowQuestions,
                        kind = PaperButtonKind.SECONDARY,
                    )
                    PaperButton(
                        "Источники (${state.notebook?.resources?.size ?: 0})",
                        onShowSources,
                        kind = PaperButtonKind.SECONDARY,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResearchQuestionsPane(
    state: ChatState,
    saving: Boolean,
    onNewQuestion: () -> Unit,
    onSelectQuestion: (String) -> Unit,
    onCollapse: () -> Unit,
    closeGlyph: String = "‹",
) {
    ResearchPaneHeader("Вопросы", state.questions.size, "Скрыть вопросы", closeGlyph, onCollapse)
    PaperDivider()
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp),
    ) {
        item(key = "new-research-question") {
            PaperButton(
                "+  Новый вопрос",
                onNewQuestion,
                Modifier.fillMaxWidth(),
                enabled = !saving,
                accessibilityLabel = "Новый вопрос",
            )
        }
        if (state.questions.isEmpty()) item(key = "empty-research-questions") {
            PaperText("История пока пуста", role = PaperTextRole.LABEL,
                color = LocalPaperColors.current.secondaryText, modifier = Modifier.padding(8.dp))
        }
        items(state.questions, key = { "question:${it.id}" }) { question ->
            val title = question.messages.firstOrNull { it.role == ChatRole.USER }?.text
                ?.trim()?.ifBlank { null } ?: "Новый вопрос"
            PaperListRow(
                label = title,
                selected = state.current?.id == question.id,
                enabled = !saving,
                onClick = { onSelectQuestion(question.id) },
            )
        }
    }
}

@Composable
private fun ResearchSourcesPane(
    state: ChatState,
    saving: Boolean,
    showLinkField: Boolean,
    onShowLinkFieldChange: (Boolean) -> Unit,
    onAddWebsite: suspend (String) -> Result<Unit>,
    onSearchResources: suspend (String) -> Result<List<SearchHit>>,
    onAddSearchResult: suspend (SearchHit) -> Result<Unit>,
    onPickFiles: () -> Unit,
    onRemoveResource: (String) -> Unit,
    onCollapse: () -> Unit,
    closeGlyph: String = "›",
) {
    val resources = state.notebook?.resources.orEmpty()
    var selectedId by remember(state.notebook?.id) { mutableStateOf<String?>(resources.firstOrNull()?.id) }
    var url by remember(state.notebook?.id) { mutableStateOf("") }
    var error by remember(state.notebook?.id) { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var searchQuery by remember(state.notebook?.id) { mutableStateOf("") }
    var searchResults by remember(state.notebook?.id) { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember(state.notebook?.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(resources.map { it.id }) {
        if (selectedId !in resources.map { it.id }) selectedId = resources.firstOrNull()?.id
    }

    ResearchPaneHeader("Источники", resources.size, "Скрыть источники", closeGlyph, onCollapse)
    PaperDivider()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PaperText("Общие для всех вопросов", role = PaperTextRole.LABEL,
            color = LocalPaperColors.current.secondaryText)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            PaperField(
                searchQuery,
                { searchQuery = it; searchResults = emptyList(); searchError = null },
                "Поиск источников",
                Modifier.weight(1f),
                enabled = !searching && !saving,
                errorMessage = searchError,
            )
            PaperButton(
                if (searching) "Ищу…" else "Найти",
                onClick = {
                    val captured = searchQuery.trim()
                    if (captured.isEmpty()) searchError = "Введите поисковый запрос."
                    else {
                        searching = true
                        scope.launch {
                            try {
                                onSearchResources(captured).fold(
                                    onSuccess = {
                                        searchResults = it
                                        searchError = if (it.isEmpty()) "Ничего не найдено." else null
                                    },
                                    onFailure = { searchError = it.message ?: "Не удалось выполнить поиск." },
                                )
                            } finally { searching = false }
                        }
                    }
                },
                enabled = !searching && !saving && searchQuery.isNotBlank(),
                busy = searching,
            )
        }
        if (searchResults.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(searchResults, key = { "search:${it.url}" }) { hit ->
                    val added = resources.any { it.url == hit.url }
                    PaperListRow(
                        label = hit.title.ifBlank { hit.url },
                        secondary = hit.url.substringAfter("://").substringBefore('/').removePrefix("www."),
                        enabled = !saving && !adding,
                        onClick = {},
                        trailing = {
                            PaperButton(
                                if (added) "Добавлен" else "Добавить",
                                onClick = {
                                    adding = true
                                    scope.launch {
                                        try { error = onAddSearchResult(hit).exceptionOrNull()?.message }
                                        finally { adding = false }
                                    }
                                },
                                kind = PaperButtonKind.QUIET,
                                enabled = !added && !saving && !adding,
                            )
                        },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperButton("Ссылка", { onShowLinkFieldChange(!showLinkField) }, Modifier.weight(1f),
                kind = PaperButtonKind.QUIET, accessibilityLabel = "Добавить ссылку")
            PaperButton("Файлы", onPickFiles, Modifier.weight(1f), enabled = !saving,
                kind = PaperButtonKind.QUIET, accessibilityLabel = "Добавить файлы")
        }
        if (showLinkField) {
            PaperField(
                url,
                { url = it; error = null },
                "Ссылка на сайт",
                Modifier.fillMaxWidth(),
                enabled = !adding,
                errorMessage = error,
            )
            PaperButton(
                if (adding) "Добавляю…" else "Добавить",
                onClick = {
                    val captured = url
                    if (researchUrl(captured) == null) error = "Введите ссылку: https://…"
                    else {
                        adding = true
                        scope.launch {
                            try {
                                val result = onAddWebsite(captured)
                                if (result.isSuccess) {
                                    if (url == captured) url = ""
                                    error = null
                                    onShowLinkFieldChange(false)
                                } else error = result.exceptionOrNull()?.message
                            } finally { adding = false }
                        }
                    }
                },
                enabled = !adding && !saving && url.isNotBlank(),
                busy = adding,
            )
        }
        if (!showLinkField) error?.let {
            PaperText(it, role = PaperTextRole.LABEL, color = LocalPaperColors.current.error)
        }
        PaperDivider()
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 8.dp)) {
            if (resources.isEmpty()) item(key = "empty-research-sources") {
                PaperText("Источников пока нет", role = PaperTextRole.LABEL,
                    color = LocalPaperColors.current.secondaryText, modifier = Modifier.padding(vertical = 8.dp))
            }
            itemsIndexed(resources, key = { _, resource -> "resource:${resource.id}" }) { index, resource ->
                ResearchSourceRow(
                    index = index + 1,
                    resource = resource,
                    selected = selectedId == resource.id,
                    enabled = !saving,
                    onSelect = { selectedId = resource.id },
                    onOpen = resource.url.takeIf { it.isNotEmpty() }?.let { address -> {
                        try { uriHandler.openUri(address) }
                        catch (failure: Exception) {
                            AppLog.error("chat", "source.open.failed", failure, mapOf("resourceId" to resource.id))
                            error = "Не удалось открыть источник."
                        }
                    } },
                    onRemove = { onRemoveResource(resource.id) },
                )
            }
            val selected = resources.firstOrNull { it.id == selectedId }
            if (selected != null) item(key = "selected-source:${selected.id}") {
                val index = resources.indexOf(selected) + 1
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaperDivider()
                    Spacer(Modifier.height(2.dp))
                    PaperText("Источник $index", role = PaperTextRole.LABEL,
                        color = LocalPaperColors.current.action)
                    PaperText(selected.title, role = PaperTextRole.TITLE)
                    PaperPanel(kind = PaperSurfaceKind.SELECTED) {
                        PaperText(
                            selected.snippet.ifBlank {
                                if (selected.attachment != null) "Файл доступен ИИ во всех вопросах."
                                else "Источник доступен ИИ во всех следующих запросах."
                            },
                            Modifier.fillMaxWidth().padding(12.dp),
                            role = PaperTextRole.BODY,
                        )
                    }
                    if (selected.url.isNotEmpty()) PaperLink("Открыть источник  →", {
                        try { uriHandler.openUri(selected.url) }
                        catch (failure: Exception) {
                            AppLog.error("chat", "source.open.failed", failure, mapOf("resourceId" to selected.id))
                            error = "Не удалось открыть источник."
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun ResearchPaneHeader(
    title: String,
    count: Int,
    collapseLabel: String,
    collapseGlyph: String,
    onCollapse: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PaperText(title, role = PaperTextRole.HEADLINE)
        PaperResearchCountBadge(count.toString())
        Spacer(Modifier.weight(1f))
        PaperIconButton(collapseLabel, onCollapse) {
            PaperText(collapseGlyph, role = PaperTextRole.TITLE, color = LocalPaperColors.current.action)
        }
    }
}

@Composable
private fun ResearchSourceRow(
    index: Int,
    resource: ResearchResource,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onOpen: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    var menuOpen by remember(resource.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PaperResearchCountBadge(index.toString(), selected = selected)
        PaperListRow(
            label = resource.title,
            modifier = Modifier.weight(1f),
            selected = selected,
            enabled = enabled,
            onClick = onSelect,
            secondary = resourceSubtitle(resource),
            trailing = {
                Box {
                    PaperIconButton("Действия с источником ${resource.title}", { menuOpen = true }) {
                        PaperText("⋮", role = PaperTextRole.TITLE, color = LocalPaperColors.current.secondaryText)
                    }
                    PaperMenuHost(menuOpen, { menuOpen = false }) {
                        if (onOpen != null) PaperMenuAction("Открыть", { menuOpen = false; onOpen() })
                        PaperMenuAction("Убрать", { menuOpen = false; onRemove() }, enabled = enabled, destructive = true)
                    }
                }
            },
        )
    }
}

private fun resourceSubtitle(resource: ResearchResource): String = when {
    resource.attachment != null -> checkNotNull(resource.attachment).name.substringAfterLast('.', "")
        .ifBlank { "Файл" }.uppercase()
    resource.url.isNotEmpty() -> resource.url.substringAfter("://").substringBefore('/').removePrefix("www.")
    resource.discovered -> "Найден при поиске"
    else -> "Источник"
}

private const val QUESTIONS_PANEL = "questions"
private const val SOURCES_PANEL = "sources"
