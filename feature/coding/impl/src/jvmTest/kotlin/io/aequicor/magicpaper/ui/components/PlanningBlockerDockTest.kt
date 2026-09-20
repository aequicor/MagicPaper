package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.io.File
import kotlin.test.*

class PlanningBlockerDockTest {
    @Test fun reasonAndRetryStayAccessibleAtNarrowAndWideWidths() {
        val issue = PlanningIssue(IssueKind.VERIFICATION,
            "Не подтверждены удаление производных индексов, блокировка продвижения при ухудшении метрик и восстановление прежней версии вместе с её разрешениями при откате.", requiresUser = true)
        val stage = Milestone("learning", "Локальный опыт и проверяемые улучшения")
        val attempt = StageAttempt("attempt", "worker", StageAssignment("profile", "model"), error = issue, repairRetries = 2)
        val blockers = listOf(PlanningBlocker("plan", issue, stage, attempt))
        for (width in listOf(390, 1000)) {
            val busy = mutableStateOf(false)
            var cardHeight = 0
            var clicks = 0
            ImageComposeScene(width, 420) {
                MagicPaperTheme { Surface { Column {
                    PlanningBlockerCard(blockers, busy.value, { clicks++ },
                        Modifier.fillMaxWidth().heightIn(max = 260.dp).onSizeChanged { cardHeight = it.height })
                    OutlinedTextField("", {}, placeholder = { Text("Поручение агенту…") }, modifier = Modifier.fillMaxWidth())
                } } }
            }.use { scene ->
                var frame = 0L
                fun render() { repeat(5) { scene.render(++frame * 16_000_000L).close() } }
                fun click() {
                    scene.sendPointerEvent(PointerEventType.Press, Offset(100f, cardHeight - 32f))
                    scene.sendPointerEvent(PointerEventType.Release, Offset(100f, cardHeight - 32f))
                    render()
                }
                render()
                assertTrue(cardHeight in 100..260)
                val output = File("build/reports/planning-blocker").apply { mkdirs() }
                File(output, "blocked-$width.png").writeBytes(scene.render(++frame * 16_000_000L).use {
                    it.encodeToData()!!.use { data -> data.bytes }
                })
                click()
                assertEquals(1, clicks, "Retry button is visible and clickable")
                busy.value = true; render(); click()
                assertEquals(1, clicks, "A running retry cannot be submitted again")
            }
        }
    }
}
