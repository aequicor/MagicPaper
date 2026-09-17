package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.ui.ResearchBrowserPhase
import io.aequicor.magicpaper.ui.ResearchBrowserState

@Composable
internal fun ResearchBrowserDialog(state: ResearchBrowserState, onRead: () -> Unit, onDismiss: () -> Unit) {
    PaperWideDialog(onDismiss, Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
        ResearchBrowserContent(state, onRead, onDismiss)
    }
}

@Composable
internal fun ResearchBrowserContent(state: ResearchBrowserState, onRead: () -> Unit, onDismiss: () -> Unit) {
    val busy = state.phase == ResearchBrowserPhase.OPENING || state.phase == ResearchBrowserPhase.READING
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PaperText("Прочитать в браузере", role = PaperTextRole.TITLE)
        PaperText(state.title, role = PaperTextRole.CHROME)
        PaperText(when (state.phase) {
            ResearchBrowserPhase.OPENING -> "Открываю браузер…"
            ResearchBrowserPhase.READING -> "Читаю страницу…"
            else -> if (state.pageOpen) "Пройдите проверку в открывшемся окне браузера, затем нажмите «Прочитать страницу»."
                else "Повторите открытие страницы в браузере."
        }, role = PaperTextRole.LABEL)
        state.problem?.let { PaperText(it, role = PaperTextRole.LABEL, color = LocalPaperColors.current.error) }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperButton("Отмена", onDismiss, kind = PaperButtonKind.QUIET)
            PaperButton(if (state.phase == ResearchBrowserPhase.FAILED && !state.pageOpen) "Повторить открытие" else "Прочитать страницу",
                onRead, enabled = !busy, busy = busy)
        }
    }
}
