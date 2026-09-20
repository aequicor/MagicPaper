package io.aequicor.magicpaper.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
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
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
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
            val forms = CodingFormDrafts(io.aequicor.magicpaper.data.storage.InMemoryDraftRepository(), backgroundScope)
            val session = CodingSession("parent", "project", "Личный кабинет", 1)
            val output = File("build/reports/message-scheduler").apply { mkdirs() }
            for (width in listOf(1280, 430)) {
                ImageComposeScene(width, 1150) {
                    MagicPaperTheme { Surface {
                        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Личный кабинет · Оркестратор 1", style = MaterialTheme.typography.titleLarge)
                            Text("Ожидание события или времени")
                            ScheduledMessages(p, {}, { forms.schedule(session, p, it) }, { _, _ -> })
                            HorizontalDivider()
                            Text("Сессия исполнителя", style = MaterialTheme.typography.titleMedium)
                            OrchestrationMessageRoute(message, io.aequicor.magicpaper.ui.CodingPlanningState()) {}
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

    @Test fun scheduledEditorRestoresRawDraftInRealPaperFieldAndCancelClearsIt() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val repository = io.aequicor.magicpaper.data.storage.InMemoryDraftRepository()
            val session = CodingSession("parent", "project", "Session", 1)
            val rule = ScheduledMessage("rule", "plan", "run", "parent", MessageTrigger(MessageTriggerKind.AT_TIME, at = 1),
                "parent", null, "Message", 1, deliveryId = "delivery")
            val plan = Plan("plan", "project", "Goal", runId = "run", scheduledMessages = listOf(rule))
            val first = CodingFormDrafts(repository, backgroundScope)
            first.schedule(session, plan, rule).update("  unfinished raw edit  ")
            first.flush(); first.revoke()
            val reopened = CodingFormDrafts(repository, backgroundScope)
            val form = reopened.schedule(session, plan, rule)
            runCurrent()
            ImageComposeScene(430, 900) {
                MagicPaperTheme { Surface { Column(Modifier.fillMaxSize().padding(12.dp)) {
                    ScheduledMessages(plan, {}, { form }, { _, _ -> fail("Restoring must not send") })
                } } }
            }.use { scene ->
                var frame = 0L
                fun draw() { repeat(6) { scene.render(++frame * 16_000_000L).close(); runCurrent() } }
                fun nodes(): List<SemanticsNode> {
                    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                    return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                }
                fun text(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::text)
                fun click(label: String) {
                    val button = nodes().single { label in text(it) && it.config.getOrNull(SemanticsActions.OnClick) != null }
                    assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
                    draw()
                }
                draw(); click("Изменить через чат")
                val field = nodes().single { it.config.getOrNull(SemanticsActions.SetText) != null }
                assertEquals("  unfinished raw edit  ", field.config[SemanticsProperties.EditableText].text)
                field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("  corrected but not sent  "))
                draw()
                assertEquals("  corrected but not sent  ", form.draft.state.value.value.text)
                val output = File("build/reports/message-scheduler").apply { mkdirs() }
                scene.render(++frame * 16_000_000L).use { image ->
                    File(output, "scheduled-edit-restored-430.png").writeBytes(image.encodeToData()!!.use { it.bytes })
                }
                click("Отмена")
                assertEquals("", form.draft.state.value.value.text)
                assertTrue(repository.keys("coding-form/").isEmpty())
                assertTrue(nodes().none { it.config.getOrNull(SemanticsActions.SetText) != null })
            }
        } finally { Dispatchers.resetMain() }
    }
}
