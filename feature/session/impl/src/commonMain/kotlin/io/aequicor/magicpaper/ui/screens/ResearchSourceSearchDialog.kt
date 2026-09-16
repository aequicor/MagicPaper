package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
    onAddWebsite: suspend (String, ResearchResourceScope) -> Result<Unit>, onDismiss: () -> Unit) {
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
                } else onSearch(captured).fold({
                    results = it
                    if (it.isEmpty()) error = "Ничего не найдено. Измените запрос."
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResearchResourceScope.entries.forEach { option ->
                    PaperChoice(target == option, { target = option }, option.label, enabled = !busy)
                }
            }
            PaperField(query, { query = it; error = null; results = emptyList() }, "Запрос или ссылка", Modifier.fillMaxWidth(), enabled = !busy, errorMessage = error)
            PaperButton(if (busy) "Ищу…" else if (researchUrl(query) != null) "Сохранить ссылку" else "Найти", ::submit,
                enabled = !busy && query.isNotBlank(), busy = busy)
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(results, key = { it.url }) { hit ->
                    val added = (sharedResources + if (target == ResearchResourceScope.QUESTION) questionResources else emptyList())
                        .any { it.url == hit.url }
                    PaperListRow(hit.title.ifBlank { hit.url }, secondary = hit.url.substringAfter("://").substringBefore('/'), trailing = {
                        PaperIconButton(if (added) "Источник добавлен" else "Выбрать источник ${hit.title}", {
                            busy = true
                            val selectedScope = target
                            scope.launch {
                                try { error = onAddResult(hit, selectedScope).exceptionOrNull()?.message }
                                finally { busy = false }
                            }
                        }, enabled = !busy && !added) { PaperText(if (added) "✓" else "+") }
                    })
                }
            }
        }
    }
}
