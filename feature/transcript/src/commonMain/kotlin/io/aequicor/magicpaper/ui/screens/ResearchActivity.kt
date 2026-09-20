package io.aequicor.magicpaper.ui.screens

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalUriHandler
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog

/** Only observed operations and their outcomes; no generated thoughts or simulated progress. */
@Composable
fun ResearchActivity(steps: List<CodingStep>, busy: Boolean, paused: Boolean = false,
    failed: Boolean = false,
    onPause: (() -> Unit)? = null, onResume: (() -> Unit)? = null, answering: Boolean = false) {
    var expanded by rememberSaveable { mutableStateOf(busy || paused || failed) }
    var linkError by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val activity = remember(steps, busy, paused, answering, failed) { researchActivityEntries(steps, busy, paused, answering, failed) }
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
                detailProblem = step.detailProblem, system = step.system,
                contentLabel = if (step.sources.isNotEmpty()) "Найденные источники" else null) {
                // Only the references this individual search newly returned; the attached
                // library lives in the sources pane and must not repeat under a search step.
                step.sources.forEachIndexed { sourceIndex, source ->
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
        if (linkError) PaperText("Не удалось открыть источник. Повторите попытку.", role = PaperTextRole.LABEL, color = LocalPaperColors.current.error)
    }
}

data class ResearchActivityEntry(val title: String, val detail: String?, val heading: String,
    val state: PaperResearchStepState, val search: Boolean = false, val detailProblem: String? = null,
    val system: String? = null, val sources: List<SearchHit> = emptyList())

// Read counts are also stored in older transcripts as the web.read step's title.
private val sourceReadSummary = Regex("^(Прочитано источников: \\d+); (недоступно: ([1-9]\\d*))$")

/** Reading counts describe the operation; a pending runtime call is not a completed analysis. */
fun researchActivityEntries(steps: List<CodingStep>, busy: Boolean, paused: Boolean,
    answering: Boolean = false, failed: Boolean = false): List<ResearchActivityEntry> {
    val entries = steps.researchActivity().filter { it.isVisibleActivity }
        .distinctBy { it.id.ifBlank { "${it.kind}:${it.title}" } }.map { step ->
            val search = step.tool in researchSearchTools
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
                step.kind == CodingStepKind.ERROR && text == RESEARCH_MODEL_FAILURE ->
                    "Проверьте подключение к модели и нажмите «Продолжить»."
                readSummary != null -> readSummary.groupValues[1]
                search || checking -> text.takeUnless { it == title }
                state == PaperResearchStepState.PAUSED -> "Приостановлено"
                else -> null
            }
            ResearchActivityEntry(title, detail, when {
                search -> "Ищу дополнительные источники"
                checking -> "Читаю выбранные источники"
                else -> text
            }, state, search, detailProblem = readSummary?.groupValues?.get(2),
                system = step.system.takeIf { it.isNotBlank() },
                sources = if (search) step.sources.distinctBy { it.url } else emptyList())
        }
    // This is the observable wait between completed tools and the next runtime event,
    // not a fabricated thinking/search stage or a guessed plan.
    return if (!failed && (busy || paused) && entries.none { it.state == PaperResearchStepState.ACTIVE || it.state == PaperResearchStepState.PAUSED })
        entries + ResearchActivityEntry(if (answering) "Подготовка ответа" else "Ответ агента",
            if (paused) "Приостановлено" else if (answering) "Ответ поступает…" else "Ожидаю ответ…",
            if (answering) "Готовлю ответ" else "Ожидаю ответ агента",
            if (paused) PaperResearchStepState.PAUSED else PaperResearchStepState.ACTIVE)
    else entries
}
