package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlin.test.*

class PlanningQuestionWizardTest {
    @Test fun preservesChoicesAcrossBackNavigationAndSubmitsTogether() {
        for (width in listOf(390, 1000)) {
            val busy = mutableStateOf(false)
            var height = 0
            var submitted: List<PlanningAnswer>? = null
            val questions = listOf(
                PlanningQuestion("format", "Формат?", QuestionKind.SINGLE,
                    listOf(QuestionOption("pdf", "PDF"), QuestionOption("docx", "DOCX"))),
                PlanningQuestion("features", "Возможности?", QuestionKind.MULTIPLE,
                    listOf(QuestionOption("edit", "Редактирование"), QuestionOption("export", "Экспорт"))),
            )
            ImageComposeScene(width, 500) {
                MagicPaperTheme { Box {
                    PlanningQuestionWizard(questions, busy.value,
                        Modifier.fillMaxWidth().onSizeChanged { height = it.height }) { submitted = it }
                } }
            }.use { scene ->
                var frame = 0L
                fun render() { repeat(4) { scene.render(++frame * 16_000_000L).close() } }
                fun click(x: Float, y: Float) {
                    scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
                    scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
                    render()
                }
                fun next() = click(width - 45f, height - 30f)
                render()
                next() // An empty answer cannot advance.
                click(70f, 68f) // PDF.
                next()
                click(70f, 94f) // First multiple-choice option.
                click(70f, 129f) // Second multiple-choice option.
                click(40f, height - 30f) // Back.
                click(70f, 104f) // Replace the single choice with DOCX.
                next()
                busy.value = true
                render()
                next()
                assertNull(submitted, "Busy wizard must not submit")
                busy.value = false
                render()
                next()
                assertEquals(listOf(
                    PlanningAnswer("format", listOf("docx")),
                    PlanningAnswer("features", listOf("edit", "export")),
                ), submitted)
            }
        }
    }
}
