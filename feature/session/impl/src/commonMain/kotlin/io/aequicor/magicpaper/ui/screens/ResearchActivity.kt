package io.aequicor.magicpaper.ui.screens

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalUriHandler
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog

/** Only observed operations and their outcomes; no generated thoughts or simulated progress. */
@Composable
internal fun ResearchActivity(steps: List<CodingStep>, busy: Boolean, paused: Boolean = false,
    failed: Boolean = false, sources: List<SearchHit> = emptyList(),
    onPause: (() -> Unit)? = null, onResume: (() -> Unit)? = null, answering: Boolean = false) {
    var expanded by rememberSaveable { mutableStateOf(busy || paused || failed) }
    var linkError by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val activity = remember(steps, busy, paused, answering) { researchActivityEntries(steps, busy, paused, answering) }
    val references = remember(sources) { sources.distinctBy { it.url } }
    val sourceStep = activity.indexOfLast { it.search }
    val state = when {
        failed -> PaperResearchStepState.FAILED
        busy -> PaperResearchStepState.ACTIVE
        paused -> PaperResearchStepState.PAUSED
        else -> PaperResearchStepState.COMPLETE
    }
    val label = when {
        failed -> "Исследование прервано"
        busy -> activity.lastOrNull { it.state == PaperResearchStepState.ACTIVE }?.heading ?: "Ожидаю ответ агента"
        paused -> "Исследование приостановлено"
        else -> "Исследование завершено"
    }
    val action = if (busy) onPause else if (paused) onResume else null
    PaperResearchActivityPanel(label, state, expanded, { expanded = it },
        actionLabel = if (busy) "Остановить" else "Продолжить", onAction = action) {
        activity.forEachIndexed { index, step ->
            PaperResearchActivityStep(step.title, step.detail, step.state, last = index == activity.lastIndex,
                detailProblem = step.detailProblem,
                contentLabel = if (index == sourceStep && references.isNotEmpty()) "Доступные источники" else null) {
                // Available references include shared materials; never imply that
                // this individual search discovered the entire question library.
                if (index == sourceStep && references.isNotEmpty()) {
                    references.forEachIndexed { sourceIndex, source ->
                        PaperResearchSourceLink(source.title.ifBlank { source.url }, {
                            try { uri.openUri(source.url) }
                            catch (failure: Exception) {
                                AppLog.error("chat", "activity.source.open.failed", failure, mapOf("sourceIndex" to sourceIndex.toString()))
                                linkError = true
                            }
                        })
                    }
                }
            }
        }
        if (linkError) PaperText("Не удалось открыть источник. Повторите попытку.", role = PaperTextRole.LABEL, color = LocalPaperColors.current.error)
    }
}

internal data class ResearchActivityEntry(val title: String, val detail: String?, val heading: String,
    val state: PaperResearchStepState, val search: Boolean = false, val detailProblem: String? = null)

// Read counts are also stored in older transcripts as the web.read step's title.
private val sourceReadSummary = Regex("^(Прочитано источников: \\d+); (недоступно: ([1-9]\\d*))$")

/** Reading counts describe the operation; a pending runtime call is not a completed analysis. */
internal fun researchActivityEntries(steps: List<CodingStep>, busy: Boolean, paused: Boolean,
    answering: Boolean = false): List<ResearchActivityEntry> {
    val entries = steps.researchActivity().filter { it.isVisibleActivity }
        .distinctBy { it.id.ifBlank { "${it.kind}:${it.title}" } }.map { step ->
            val search = step.tool in setOf("web.search", "web_search", "search")
            val checking = step.tool == "web.read"
            val text = step.title.removePrefix("⚒ ")
            val state = when {
                !step.ok || step.kind == CodingStepKind.ERROR -> PaperResearchStepState.FAILED
                step.running && !busy -> PaperResearchStepState.PAUSED
                step.running -> PaperResearchStepState.ACTIVE
                else -> PaperResearchStepState.COMPLETE
            }
            val title = when { search -> "Поиск дополнительных материалов"; checking -> "Проверка выбранных источников"; else -> text }
            val readSummary = if (checking) sourceReadSummary.matchEntire(text) else null
            val detail = when {
                readSummary != null -> readSummary.groupValues[1]
                search || checking -> text.takeUnless { it == title }
                state == PaperResearchStepState.PAUSED -> "Приостановлено"
                else -> null
            }
            ResearchActivityEntry(title, detail, when {
                search -> "Ищу дополнительные источники"
                checking -> "Читаю выбранные источники"
                else -> text
            }, state, search, detailProblem = readSummary?.groupValues?.get(2))
        }
    // This is the observable wait between completed tools and the next runtime event,
    // not a fabricated thinking/search stage or a guessed plan.
    return if ((busy || paused) && entries.none { it.state == PaperResearchStepState.ACTIVE || it.state == PaperResearchStepState.PAUSED })
        entries + ResearchActivityEntry(if (answering) "Подготовка ответа" else "Ответ агента",
            if (paused) "Приостановлено" else if (answering) "Ответ поступает…" else "Ожидаю ответ…",
            if (answering) "Готовлю ответ" else "Ожидаю ответ агента",
            if (paused) PaperResearchStepState.PAUSED else PaperResearchStepState.ACTIVE)
    else entries
}
