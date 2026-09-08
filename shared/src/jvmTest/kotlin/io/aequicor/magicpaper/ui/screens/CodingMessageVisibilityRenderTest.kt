package io.aequicor.magicpaper.ui.screens

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.components.LocalHideSystemSteps
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CodingMessageVisibilityRenderTest {
    @Test fun hiddenRecordsLeaveNoBubbleOrSpacingAndKeepTheLiveStatusVisible() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val answer = CodingMessage("answer", CodingRole.AGENT, "Проверки завершены. Результат сохранён.", createdAt = 1)
            val info = CodingStep(CodingStepKind.INFO, "SKILLS: подключённых пакетов нет")
            val hiddenHistory = listOf(answer) + (1..24).map {
                CodingMessage("system-$it", CodingRole.AGENT, "", steps = listOf(info), createdAt = 1L + it)
            }
            fun render(width: Int, busy: Boolean, history: List<CodingMessage>, draftSteps: List<CodingStep>, hide: Boolean): ByteArray =
                ImageComposeScene(width, 660) {
                    MagicPaperTheme { Surface {
                        CompositionLocalProvider(LocalHideSystemSteps provides hide) {
                            CodingChat(CodingProject("p", "Проект", "/project", 1),
                                CodingSessionUi(CodingSession("s", "p", "Сессия", 1), messages = history,
                                    draft = CodingDraft(steps = draftSteps, active = busy), running = busy),
                                busy, true, { _, _ -> }, {}, { _, _ -> })
                        }
                    } }
                }.use { scene ->
                    repeat(12) { scene.render(it * 32_000_000L).close(); runCurrent() }
                    scene.render(400_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } }
                }
            val output = File("build/reports/coding-message-visibility").apply { mkdirs() }
            for (width in listOf(430, 1000)) for (busy in listOf(false, true)) {
                val expected = render(width, busy, listOf(answer), emptyList(), hide = true)
                val actual = render(width, busy, hiddenHistory, listOf(info), hide = true)
                File(output, "$width-$busy.png").writeBytes(actual)
                assertContentEquals(expected, actual, "Hidden messages must leave no pixels or gaps, including near the status")
                val shown = render(width, busy, hiddenHistory, listOf(info), hide = false)
                assertFalse(actual.contentEquals(shown), "The system-message preference must still show the original records")
            }
        } finally { Dispatchers.resetMain() }
    }
}
