package io.aequicor.magicpaper.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.use
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.components.OrchestrationMessageRoute
import io.aequicor.magicpaper.ui.components.ScheduledMessages
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import java.io.File
import kotlin.test.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessageSchedulerRenderTest {
    @Test fun rulesAndHandoffsRenderAtDesktopAndNarrowWidths() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val p = Plan("plan", "project", "Личный кабинет", parentSessionId = "parent", runId = "run", confirmedRevision = 1,
                intent = ExecutionIntent.RUN, milestones = listOf(Milestone("task", "Проверка авторизации и восстановления доступа", displayNumber = 2)),
                scheduledMessages = listOf(ScheduledMessage("rule", "plan", "run", "parent",
                    MessageTrigger(MessageTriggerKind.EVENT, event = MessageEventKind.TASK_SUCCEEDED, taskId = "task", deadline = 1788868800000),
                    "parent", null, "После проверки авторизации подготовь краткий итог для пользователя.", 1, deliveryId = "delivery")))
            val message = CodingMessage("transfer", CodingRole.AGENT, "Результат передан оркестратору", createdAt = 1,
                route = MessageRoute(SessionAddress("worker", "Проверка авторизации и восстановления доступа", "Исполнитель · Этап 2"),
                    SessionAddress("parent", "Личный кабинет", "Оркестратор 1"), kind = "Результат", stageLabel = "Этап 2"),
                handoff = HandoffInfo("event", "task", "run", HandoffStatus.RESOLVED, "Ожидается завершение проверки; повторный вызов исполнителя не требуется"))
            val output = File("build/reports/message-scheduler").apply { mkdirs() }
            for (width in listOf(1280, 430)) {
                ImageComposeScene(width, 1150) {
                    MagicPaperTheme { Surface {
                        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Личный кабинет · Оркестратор 1", style = MaterialTheme.typography.titleLarge)
                            Text("Ожидание события или времени")
                            ScheduledMessages(p, {}, { _, _ -> })
                            HorizontalDivider()
                            Text("Сессия исполнителя", style = MaterialTheme.typography.titleMedium)
                            OrchestrationMessageRoute(message, null) {}
                            Text(message.text)
                        }
                    } }
                }.use { scene ->
                    repeat(6) { scene.render(it * 16_000_000L).close(); runCurrent() }
                    File(output, "scheduler-$width.png").writeBytes(scene.render(112_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })
                }
            }
        } finally { Dispatchers.resetMain() }
    }
}
