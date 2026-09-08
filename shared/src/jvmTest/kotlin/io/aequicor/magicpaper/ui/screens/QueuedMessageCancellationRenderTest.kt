package io.aequicor.magicpaper.ui.screens

import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.use
import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
class QueuedMessageCancellationRenderTest {
    @Test fun queuedBubbleCancelsOnClickAndUpdatesEvenBeforeHistoryRefreshes() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            for (width in listOf(1000, 360)) {
                val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
                val kv = InMemoryKeyValueStore()
                val projects = JsonCodingProjectRepository(kv, json)
                val profiles = JsonLlmProfileRepository(kv, json)
                val settings = JsonSettingsRepository(kv, json)
                val store = PlanningStore(JsonPlanningRepository(kv, json))
                val gate = CompletableDeferred<Unit>()
                val gateway = object : LlmGateway {
                    override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
                        gate.await()
                        return """{"intent":"DISCUSS","reply":"Продолжаю работу"}"""
                    }
                }
                val execution = PlanningExecutionService(store, NoopCodingRuntime, projects, profiles, settings,
                    object : MilestoneVerifier {
                        override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) =
                            Verdict(true, "Checked")
                    }, scope = backgroundScope)
                val service = OrchestrationService(store, execution, projects, profiles, settings,
                    PlanComposer(gateway), gateway, backgroundScope)
                val project = CodingProject("project", "MagicPaper", "/project", 1)
                val parent = CodingSession("parent", project.id, "План проекта", 1, planningMode = true)
                val profile = LlmProfile("model", "Planner", baseUrl = "http://test/v1", modelId = "m")
                projects.save(project); projects.saveSession(parent); profiles.save(profile)
                settings.save(AppSettings(activeLlmProfileId = profile.id))
                store.save(Plan("plan", project.id, parent.name, parentSessionId = parent.id,
                    dialogue = listOf(PlanningMessage("previous", "assistant", "План подготовлен"))))
                service.bootstrap(); runCurrent()
                service.send(parent, "Объясни обновлённый план"); runCurrent()
                service.send(parent, "Подтверждаете запуск обновлённого плана с контрольной передачей результатов?\nда"); runCurrent()
                // Keep this snapshot unchanged: the receipt must observe the authoritative inbox itself.
                val messages = projects.messages(project.id, parent.id).filter { it.inputStatus != null }
                assertEquals(listOf(OrchestrationInputStatus.PROCESSING, OrchestrationInputStatus.QUEUED), messages.map { it.inputStatus })
                ImageComposeScene(width, 900) {
                    MagicPaperTheme { Surface {
                        CodingChat(project, CodingSessionUi(parent, messages, draft = service.drafts.value.getValue(parent.id),
                            running = true, plan = store.plans.value.single()),
                            busy = true, engineReady = true, onSend = { _, _ -> }, onAbort = {},
                            onPickAttachments = { _, _ -> }, planningService = service, allowQueue = true)
                    } }
                }.use { scene ->
                    var frame = 0L
                    fun render() { repeat(8) { scene.render(++frame * 16_000_000L).close(); runCurrent() } }
                    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                    fun nodes() = scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                    fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
                    fun snapshot(name: String) {
                        val output = File("build/reports/queued-message-cancellation").apply { mkdirs() }
                        File(output, "$name-$width.png").writeBytes(scene.render(++frame * 16_000_000L).use {
                            it.encodeToData()!!.use { data -> data.bytes }
                        })
                    }
                    render()
                    val cancel = nodes().single { text(it) == "Отменить отправку" }
                    assertNotNull(cancel.config.getOrNull(SemanticsActions.OnClick))
                    assertNull(cancel.config.getOrNull(SemanticsProperties.Disabled))
                    val bounds = cancel.boundsInRoot
                    assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= 900)
                    snapshot("queued")
                    scene.sendPointerEvent(PointerEventType.Press, bounds.center)
                    scene.sendPointerEvent(PointerEventType.Release, bounds.center)
                    render()
                    assertTrue(nodes().none { text(it) == "Отменить отправку" })
                    assertTrue(nodes().any { text(it) == "Отправка отменена" })
                    assertTrue(nodes().any { text(it) == "Оркестратор обрабатывает сообщение" })
                    assertEquals(OrchestrationInputStatus.WITHDRAWN, projects.orchestration(parent.id)!!.inputs.last().status)
                    assertEquals(OrchestrationInputStatus.PROCESSING, projects.orchestration(parent.id)!!.inputs.first().status)
                    snapshot("cancelled")
                }
                service.shutdown()
            }
        } finally { Dispatchers.resetMain() }
    }
}
