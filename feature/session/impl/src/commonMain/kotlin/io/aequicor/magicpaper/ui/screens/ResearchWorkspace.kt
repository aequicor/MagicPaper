package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
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
    ResearchWorkspaceContent(state, saving, error,
        onNewQuestion = { if (state.notebook == null) vm.newSession() else edit { vm.newQuestion() } },
        onSelectQuestion = { id -> edit { vm.selectQuestion(id) } },
        onAddWebsite = { url ->
            val id = rootId()
            vm.addWebsite(id, url)
        },
        onPickFiles = {
            val id = rootId()
            vm.pickAttachments(0) { files -> edit { vm.addResources(id, files) } }
        },
        onRemoveResource = { id -> state.notebook?.id?.let { chatId -> edit { vm.removeResource(chatId, id) } } },
        content = content)
}

@Composable
internal fun ResearchWorkspaceContent(
    state: ChatState,
    saving: Boolean = false,
    error: String? = null,
    onNewQuestion: () -> Unit = {},
    onSelectQuestion: (String) -> Unit = {},
    onAddWebsite: suspend (String) -> Result<Unit> = { Result.success(Unit) },
    onPickFiles: () -> Unit = {},
    onRemoveResource: (String) -> Unit = {},
    content: @Composable () -> Unit,
) {
    var panel by remember(state.notebook?.id) { mutableStateOf<String?>(null) }
    val inset = LocalWindowToolbarHeight.current ?: 56.dp
    Column(Modifier.fillMaxSize().padding(top = inset + PaperTitleBarLaneGap)) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PaperButton("Новый вопрос", onNewQuestion, enabled = !saving)
            PaperButton("Вопросы (${state.questions.size})", { panel = "questions" }, kind = PaperButtonKind.SECONDARY)
            PaperButton("Источники (${state.notebook?.resources?.size ?: 0})", { panel = "sources" }, kind = PaperButtonKind.SECONDARY)
        }
        error?.let { PaperText(it, Modifier.padding(12.dp), role = PaperTextRole.LABEL, color = LocalPaperColors.current.error) }
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 980.dp
            Row(Modifier.fillMaxSize()) {
                if (wide) {
                    ResearchLibrary(state, null, saving, onSelectQuestion, onAddWebsite, onPickFiles, onRemoveResource,
                        Modifier.width(280.dp).fillMaxHeight().padding(start = 12.dp, end = 8.dp))
                    PaperVerticalDivider(Modifier.fillMaxHeight())
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    CompositionLocalProvider(LocalWindowToolbarHeight provides 0.dp, content = content)
                }
            }
        }
    }
    panel?.let { selectedPanel ->
        PaperWideDialog(onDismissRequest = { panel = null }, modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(.85f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PaperText(if (selectedPanel == "questions") "Вопросы" else "Источники", role = PaperTextRole.TITLE)
                PaperButton("Закрыть", { panel = null }, kind = PaperButtonKind.QUIET)
            }
            ResearchLibrary(state, selectedPanel, saving,
                onSelectQuestion = { onSelectQuestion(it); panel = null }, onAddWebsite, onPickFiles, onRemoveResource,
                Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ResearchLibrary(
    state: ChatState, panel: String?, saving: Boolean,
    onSelectQuestion: (String) -> Unit,
    onAddWebsite: suspend (String) -> Result<Unit>,
    onPickFiles: () -> Unit, onRemoveResource: (String) -> Unit, modifier: Modifier = Modifier,
) {
    var url by remember(state.notebook?.id) { mutableStateOf("") }
    var error by remember(state.notebook?.id) { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (panel != "sources") {
            item(key = "research-questions-heading") {
                if (panel == null) PaperText("Вопросы", role = PaperTextRole.TITLE, modifier = Modifier.padding(vertical = 8.dp))
                if (state.questions.isEmpty()) PaperText("Здесь появится история вопросов", role = PaperTextRole.LABEL)
            }
            items(state.questions, key = { "question:${it.id}" }) { question ->
                PaperListRow(question.messages.firstOrNull { it.role == ChatRole.USER }?.text ?: "Новый вопрос",
                    selected = state.current?.id == question.id, enabled = !saving,
                    secondary = when {
                        question.pendingRun?.intent == ExecutionIntent.RUN -> "Исследование выполняется"
                        question.pendingRun != null -> "Приостановлено"
                        state.current?.id == question.id -> "Выбран"
                        else -> "${question.messages.count { it.role == ChatRole.USER }} запросов"
                    }, onClick = { onSelectQuestion(question.id) })
            }
        }
        if (panel != "questions") {
            item(key = "research-sources-controls") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (panel == null) PaperText("Источники", role = PaperTextRole.TITLE, modifier = Modifier.padding(top = 16.dp))
                    PaperText("Общие для всех вопросов", role = PaperTextRole.CHROME, color = LocalPaperColors.current.secondaryText)
                    PaperField(url, { url = it; error = null }, "Ссылка на сайт", Modifier.fillMaxWidth(),
                        enabled = !adding, errorMessage = error)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaperButton(if (adding) "Добавление…" else "Добавить ссылку", {
                            val captured = url
                            if (researchUrl(captured) == null) error = "Введите ссылку на сайт: https://…"
                            else {
                                adding = true
                                scope.launch {
                                    try {
                                        val result = onAddWebsite(captured)
                                        if (result.isSuccess) { if (url == captured) url = ""; error = null }
                                        else error = result.exceptionOrNull()?.message
                                    } finally { adding = false }
                                }
                            }
                        }, enabled = !adding && !saving && url.isNotBlank())
                        PaperButton("Добавить файлы", onPickFiles, enabled = !saving, kind = PaperButtonKind.SECONDARY)
                    }
                    PaperText("Изменения учитываются в следующих запросах", role = PaperTextRole.CHROME,
                        color = LocalPaperColors.current.secondaryText)
                    if (state.notebook?.resources.isNullOrEmpty()) PaperText("Источников пока нет", role = PaperTextRole.LABEL)
                }
            }
            items(state.notebook?.resources.orEmpty(), key = { "resource:${it.id}" }) { resource ->
                PaperPanel {
                    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PaperText(resource.title, role = PaperTextRole.LABEL)
                        PaperText(if (resource.discovered) "Найден при поиске" else if (resource.attachment != null) "Файл" else "Добавлен вами",
                            role = PaperTextRole.CHROME, color = LocalPaperColors.current.secondaryText)
                        if (resource.url.isNotEmpty() && resource.title != resource.url)
                            PaperText(resource.url, role = PaperTextRole.CHROME)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (resource.url.isNotEmpty()) PaperButton("Открыть", {
                                try { uriHandler.openUri(resource.url) }
                                catch (failure: Exception) {
                                    AppLog.error("chat", "source.open.failed", failure, mapOf("resourceId" to resource.id))
                                    error = "Не удалось открыть сайт. Повторите попытку."
                                }
                            }, kind = PaperButtonKind.QUIET)
                            PaperButton("Убрать", { onRemoveResource(resource.id) }, enabled = !saving, kind = PaperButtonKind.QUIET)
                        }
                    }
                }
            }
        }
    }
}
