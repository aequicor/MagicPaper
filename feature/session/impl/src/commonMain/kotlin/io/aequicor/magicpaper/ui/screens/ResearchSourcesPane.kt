package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.ui.ChatState

@Composable
internal fun ColumnScope.ResearchSourcesPane(state: ChatState, saving: Boolean,
    onAddWebsite: suspend (String, ResearchResourceScope) -> Result<Unit>,
    onSearchResources: suspend (String) -> Result<List<SearchHit>>,
    onAddSearchResult: suspend (SearchHit, ResearchResourceScope) -> Result<Unit>,
    onPickFiles: (ResearchResourceScope) -> Unit,
    onRemoveResource: (String, ResearchResourceScope) -> Unit,
    onResourceEnabled: (String, Boolean) -> Unit,
    onShareResource: (String) -> Unit, onCollapse: () -> Unit,
    target: ResearchResourceScope, onScopeChange: (ResearchResourceScope) -> Unit,
    loadSourceIcon: suspend (String) -> ImageBitmap?,
    onResourcesEnabled: (Set<String>, Boolean) -> Unit,
    onReadSourceInBrowser: (String) -> Unit) {
    var searchOpen by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var emptyAddMenu by remember { mutableStateOf(false) }
    var filtering by remember(target) { mutableStateOf(false) }
    var query by remember(target) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    val shared = state.notebook?.resources.orEmpty()
    val local = state.current?.questionResources.orEmpty().filterNot { resource -> shared.any { it.key == resource.key } ||
        resource.discovered && resource.url in state.notebook?.excludedResourceUrls.orEmpty() }
    val disabled = state.current?.disabledResourceKeys.orEmpty()
    val resources = if (target == ResearchResourceScope.SHARED) shared else local
    val visible = remember(resources, query) { resources.filter { query.isBlank() ||
        it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true) ||
        it.attachment?.name?.contains(query, ignoreCase = true) == true } }
    @Composable fun AddMenu(expanded: Boolean, dismiss: () -> Unit) {
        PaperMenuHost(expanded, dismiss) {
            PaperMenuAction("Прикрепить файлы", { dismiss(); onPickFiles(target) },
                Modifier.semantics { contentDescription = "Добавить файлы: ${target.label}" }, enabled = !saving,
                leadingIcon = { PaperNoteAddIcon() })
            PaperMenuAction("Добавить ссылку или найти", { dismiss(); searchOpen = true }, enabled = !saving)
        }
    }
    ResearchPaneHeader("Источники", "Скрыть источники", PaperPanelSide.RIGHT, onCollapse) {
        Box {
            PaperIconButton("Добавить источник: ${target.label}", { addMenu = true }, enabled = !saving) { PaperNoteAddIcon() }
            AddMenu(addMenu) { addMenu = false }
        }
    }
    PaperResearchSourceTabs(shared.size, local.size, target == ResearchResourceScope.QUESTION,
        { onScopeChange(if (it) ResearchResourceScope.QUESTION else ResearchResourceScope.SHARED) },
        Modifier.padding(horizontal = 8.dp))
    if (resources.isNotEmpty()) {
        PaperResearchSourceSelection(target.label, resources.count { it.key !in disabled }, resources.size,
            { onResourcesEnabled(resources.map { it.key }.toSet(), it) },
            { filtering = !filtering; query = "" }, Modifier.padding(horizontal = 8.dp),
            enabled = !saving && state.current != null, searching = filtering)
        if (filtering) PaperField(query, { query = it }, "Поиск по названию или сайту",
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
        PaperDivider(Modifier.padding(horizontal = 12.dp))
    }
    if (resources.isEmpty()) {
        Box {
            PaperResearchSourcesEmpty(target == ResearchResourceScope.QUESTION, { emptyAddMenu = true }, enabled = !saving)
            AddMenu(emptyAddMenu) { emptyAddMenu = false }
        }
        Spacer(Modifier.weight(1f))
    } else key(target) {
        PaperLazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            if (visible.isEmpty()) item { PaperText("Источники не найдены", Modifier.padding(12.dp), role = PaperTextRole.LABEL) }
            items(visible, key = { "resource:$target:${it.id}" }) { resource ->
                var menu by remember(resource.id) { mutableStateOf(false) }
                val source = remember(resource.title, resource.url, resource.attachment?.name, resource.attachment?.mimeType) { resource.presentation() }
                val openWebsite: (() -> Unit)? = source.browserUrl?.let { url -> {
                    try { uriHandler.openUri(url); error = null }
                    catch (failure: Exception) {
                        AppLog.error("chat", "source.open.failed", failure, mapOf("resourceId" to resource.id))
                        error = "Не удалось открыть источник. Повторите попытку."
                    }
                } }
                val icon by produceState<ImageBitmap?>(null, source.iconUrl) {
                    value = null
                    source.iconUrl?.let { value = loadSourceIcon(it) }
                }
                PaperResearchSourceRow(source.title, resource.key !in disabled, { onResourceEnabled(resource.key, it) },
                    enabled = !saving, keepActionsVisible = menu,
                    detail = source.detail, file = source.file, icon = icon,
                    onOpenWebsite = openWebsite,
                    onRemove = { onRemoveResource(resource.id, target) },
                    readProblem = state.sourceReadProblems[state.current?.id]?.get(resource.key),
                    readProblemLabel = researchSourceProblemLabel(state.sourceReadProblems[state.current?.id]?.get(resource.key)),
                    onReadProblem = if (state.sourceBrowserSupported && resource.url.isNotEmpty()) ({ onReadSourceInBrowser(resource.key) }) else null) {
                    Box {
                        PaperIconButton("Действия с источником ${resource.title}", { menu = true }) { PaperMoreIcon() }
                        PaperMenuHost(menu, { menu = false }) {
                            if (resource.url.isNotEmpty() && state.sourceBrowserSupported) PaperMenuAction("Прочитать в браузере", {
                                menu = false; onReadSourceInBrowser(resource.key)
                            })
                            if (openWebsite != null) PaperMenuAction("Открыть", {
                                menu = false
                                openWebsite()
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
                PaperDivider(Modifier.padding(start = 36.dp, end = 4.dp), color = LocalPaperColors.current.border.copy(alpha = .25f))
            }
        }
    }
    error?.let { PaperText(it, Modifier.padding(12.dp), role = PaperTextRole.LABEL, color = LocalPaperColors.current.error) }
    if (resources.isNotEmpty()) PaperButton("Найти ещё", { searchOpen = true }, Modifier.fillMaxWidth().padding(12.dp), kind = PaperButtonKind.SECONDARY, leadingIcon = { PaperNoteAddIcon() })
    if (searchOpen) ResearchSourceSearchDialog(shared, local, onSearchResources, onAddSearchResult, onAddWebsite, { searchOpen = false }, initialScope = target)
}

