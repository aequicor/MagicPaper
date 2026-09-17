package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.launch

/** Search is explicit; picking a result is the only operation that adds it to the chosen scope. */
@Composable
internal fun ResearchSourceSearchDialog(sharedResources: List<ResearchResource>, questionResources: List<ResearchResource>,
    onSearch: suspend (String) -> Result<List<SearchHit>>,
    onAddResult: suspend (SearchHit, ResearchResourceScope) -> Result<Unit>,
    onAddWebsite: suspend (String, ResearchResourceScope) -> Result<Unit>, onDismiss: () -> Unit,
    searchLabel: String = "") {
    var query by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(ResearchResourceScope.QUESTION) }
    var results by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun submit() {
        if (busy || query.isBlank()) return
        val captured = query.trim()
        val selectedScope = target
        busy = true
        error = null
        scope.launch {
            try {
                if (researchUrl(captured) != null) {
                    onAddWebsite(captured, selectedScope).fold({ onDismiss() }, { error = it.message })
                } else onSearch(captured).fold({ hits ->
                    // Already attached sources are not search results: the library shows them itself.
                    val attached = (sharedResources + questionResources).map { it.url }.toSet()
                    results = hits.filterNot { it.url in attached }
                    error = when {
                        hits.isEmpty() -> "Ничего не найдено. Измените запрос."
                        results.isEmpty() -> "Новых источников нет: всё найденное уже прикреплено."
                        else -> null
                    }
                }, { error = it.message })
            } finally { busy = false }
        }
    }
    PaperWideDialog(onDismissRequest = onDismiss, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 640.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PaperText("Найти источники", Modifier.weight(1f), role = PaperTextRole.TITLE)
                PaperIconButton("Закрыть поиск источников", onDismiss) { PaperText("×") }
            }
            // Search can be billed by an external API: name the configured system before it runs.
            if (searchLabel.isNotBlank()) PaperText("Поиск: $searchLabel", role = PaperTextRole.CHROME,
                color = LocalPaperColors.current.secondaryText)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResearchResourceScope.entries.forEach { option ->
                    PaperChoice(target == option, { target = option }, option.label, enabled = !busy)
                }
            }
            PaperField(query, { query = it; error = null; results = emptyList() }, "Запрос или ссылка", Modifier.fillMaxWidth(), enabled = !busy, errorMessage = error)
            PaperButton(if (busy) "Ищу…" else if (researchUrl(query) != null) "Сохранить ссылку" else "Найти", ::submit,
                enabled = !busy && query.isNotBlank(), busy = busy,
                leadingIcon = if (researchUrl(query) != null) ({ PaperNoteAddIcon() }) else null)
            PaperLazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(results, key = { it.url }) { hit ->
                    val secondary = buildList {
                        add(hit.url.substringAfter("://").substringBefore('/'))
                        if (hit.provider.isNotBlank()) add(hit.provider)
                    }.joinToString(" · ")
                    PaperListRow(hit.title.ifBlank { hit.url }, secondary = secondary, trailing = {
                        PaperIconButton("Выбрать источник ${hit.title}", {
                            busy = true
                            val selectedScope = target
                            scope.launch {
                                try { error = onAddResult(hit, selectedScope).exceptionOrNull()?.message }
                                finally { busy = false }
                            }
                        }, enabled = !busy) { PaperNoteAddIcon() }
                    })
                }
            }
        }
    }
}
