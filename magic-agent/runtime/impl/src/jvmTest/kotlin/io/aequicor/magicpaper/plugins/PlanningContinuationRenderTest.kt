package io.aequicor.magicpaper.plugins

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.use
import io.aequicor.magicpaper.data.coding.*
import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.designsystem.PaperSurface
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.plugins.builtin.CodingPlanningPlugin
import io.aequicor.magicpaper.ui.CodingPlanningState
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.components.PlanningChatMessage
import io.aequicor.magicpaper.ui.components.OrchestrationStatus
import io.aequicor.magicpaper.ui.runUiSnapshot
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class PlanningContinuationRenderTest {
    @Test fun restoredRunOffersOneExplicitContinuationAndUnknownOutcomeCannotRestart() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val kv = InMemoryKeyValueStore()
            val store = TestPlanningStore(JsonPlanningRepository(kv, Json))
            val projects = journalCodingProjects(kv, Json)
            val project = CodingProject("project", "Документы", "/fixture", 1)
            projects.createTestProject(project)
            val profile = LlmProfile("agent", "Модель", baseUrl = "http://fixture/v1", modelId = "model", favoriteModels = listOf("model"), modelLibraryVersion = 1)
            val profiles = JsonLlmProfileRepository(kv, Json).also { it.save(profile) }
            val settings = JsonSettingsRepository(kv, Json).also { it.save(AppSettings(activeLlmProfileId = profile.id)) }
            var nativeCalls = 0
            val runtime = object : CodingRuntime by NoopCodingRuntime {
                override val supported = true
                override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
                override fun ensureReady() = flowOf(RuntimeStatus(RuntimePhase.READY))
                override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow<CodingEvent> {
                    assertEquals("plan", session.planId, "The displayed plan owns the action, not a sibling with the project ID")
                    nativeCalls++
                    awaitCancellation()
                }
            }
            val gateway = object : LlmGateway {
                override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = error("UI must not call a model")
            }
            val search = object : SearchEngine {
                override val provider = SearchProvider.AUTO
                override val displayName = "Auto"
                override fun isConfigured(settings: AppSettings) = true
                override suspend fun search(query: String, settings: AppSettings, limit: Int): List<SearchHit> = error("No search")
            }
            val stage = Milestone("stage", "Редактор", description = "Проверить документы", agentProfileId = profile.id)
            store.save(Plan(project.id, project.id, "Другой план", updatedAt = 1))
            val sibling = store.planFor(project.id)
            store.save(Plan("plan", project.id, "Подготовить редактор", milestones = listOf(stage), confirmedRevision = 1))
            store.command("plan", PlanningMachine.Intent.Start("saved-run", PlanningRulesSettings().snapshot(), PlanningMachine.Stamp("start", 1)))
            store.recover()
            assertEquals(ExecutionIntent.RUN, store.planFor("plan")!!.intent)
            assertNull(store.currentAdmission("plan"))
            val ports = TestPlanningExecutionPorts()
            val execution = PlanningExecutionService(store, runtime, projects, profiles, settings,
                object : MilestoneVerifier {
                    override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) = Verdict(true, "Checked")
                }, workspaces = LocalPlanningWorkspace(), scope = backgroundScope, attemptAuthority = ports, chatHooksProvider = { null })
            val plugin = CodingPlanningPlugin(store, textPlanComposer(gateway), DossierResearcher(gateway, search), execution,
                runtime, projects, profiles, settings, InMemoryDraftRepository(), backgroundScope, modelDossiers = io.aequicor.magicpaper.domain.testModelDossiers())
            val output = File("build/reports/planning-continuation").apply { mkdirs() }
            val chat = OrchestrationService(store, execution, projects, profiles, settings, textPlanComposer(gateway), gateway, backgroundScope, modelDossiers = io.aequicor.magicpaper.domain.testModelDossiers())
            val parent = CodingSession("parent", project.id, "План", 1, planningMode = true)
            val graph = CodingMessage("graph", CodingRole.AGENT, "", createdAt = 1, planning = PlanningChatBlock("plan", graph = true))
            fun checkGraph(width: Int, canContinue: Boolean) {
                ImageComposeScene(width, 600) {
                    MagicPaperTheme { PaperSurface {
                        PlanningChatMessage(graph, parent, emptyList(), chat,
                            CodingPlanningState(plans = store.plans.value, runs = store.runUiSnapshot()), {})
                    } }
                }.use { scene ->
                    repeat(4) { scene.render(it * 16_000_000L).close() }
                    assertEquals(!canContinue, scene.action("Продолжить").disabled())
                    File(output, "chat-${if (canContinue) "restored" else "unknown"}-$width.png")
                        .writeBytes(scene.render(96_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })
                }
            }
            fun checkStatus(width: Int, state: String, label: String, enabled: Boolean) {
                ImageComposeScene(width, 600) {
                    MagicPaperTheme { PaperSurface {
                        OrchestrationStatus(CodingSessionUi(parent), chat, {}, CodingPlanningState(
                            states = mapOf(parent.id to OrchestrationState(parent.id, project.id, activePlanId = "plan")),
                            plans = store.plans.value, runs = store.runUiSnapshot()))
                    } }
                }.use { scene ->
                    var frame = 0L
                    fun render() { repeat(4) { scene.render(++frame * 16_000_000L).close() } }
                    render()
                    scene.action("Подробнее ▾").config[SemanticsActions.OnClick].action!!.invoke()
                    render()
                    val action = scene.action(label)
                    assertEquals(!enabled, action.disabled(), "$state must use the current owner admission")
                    assertTrue(action.boundsInRoot.left >= 0 && action.boundsInRoot.right <= width)
                    assertTrue(action.boundsInRoot.top >= 0 && action.boundsInRoot.bottom <= 600)
                    val opposite = if (label == "Пауза") "Продолжить" else "Пауза"
                    assertTrue(scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                        .none { it.config.getOrNull(SemanticsActions.OnClick) != null && opposite in it.texts() })
                    File(output, "status-$state-$width.png").writeBytes(scene.render(++frame * 16_000_000L).use {
                        it.encodeToData()!!.use { data -> data.bytes }
                    })
                }
            }
            checkGraph(1000, true); checkGraph(390, true)
            checkStatus(1000, "restored", "Продолжить", true)
            checkStatus(390, "restored", "Продолжить", true)
            for (width in listOf(1000, 390)) {
                ImageComposeScene(width, 900) { MagicPaperTheme { PaperSurface { plugin.SessionPanel(project, Modifier) } } }.use { scene ->
                    var frame = 0L
                    fun render() { repeat(6) { scene.render(++frame * 16_000_000L).close(); runCurrent() } }
                    render()
                    assertFalse(scene.action("Продолжить").disabled())
                    assertTrue(scene.action("Пауза").disabled())
                    assertEquals(0, nativeCalls, "Opening the restored plan does not admit work")
                    assertTrue(scene.action("Продолжить").boundsInRoot.right <= width)
                    File(output, "restored-$width.png").writeBytes(scene.render(++frame * 16_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })
                    if (width == 390) {
                        scene.action("Продолжить").config[SemanticsActions.OnClick].action!!.invoke()
                        render()
                        assertEquals(1, nativeCalls, store.planFor("plan")?.issue?.message ?: execution.error.value)
                        assertTrue(scene.action("Продолжить").disabled())
                        assertFalse(scene.action("Пауза").disabled())
                        checkStatus(1000, "live", "Пауза", true)
                        checkStatus(390, "live", "Пауза", true)
                        execution.stop("plan"); runCurrent(); render()
                        assertTrue(store.runUiSnapshot().getValue("plan").requiresRecovery)
                        assertTrue(scene.action("Продолжить").disabled())
                        assertEquals(1, nativeCalls)
                        File(output, "unknown-$width.png").writeBytes(scene.render(++frame * 16_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })
                    }
                }
            }
            checkGraph(390, false)
            checkStatus(1000, "unknown", "Продолжить", false)
            checkStatus(390, "unknown", "Продолжить", false)
            for (width in listOf(1000, 390)) {
                ImageComposeScene(width, 1000) { MagicPaperTheme { PaperSurface { plugin.SessionPanel(project, Modifier) } } }.use { scene ->
                    var frame = 0L
                    fun render() { repeat(6) { scene.render(++frame * 16_000_000L).close(); runCurrent() } }
                    render()
                    assertFalse(scene.action("Проверить предыдущую работу").disabled())
                    val pending = store.unsettled("plan")
                    scene.action("Проверить предыдущую работу").config[SemanticsActions.OnClick].action!!.invoke()
                    render()
                    assertEquals(pending, store.unsettled("plan"))
                    assertEquals(1, nativeCalls, "Inspecting an unknown outcome does not start native work")
                    val confirm = scene.action("Я проверил результат")
                    assertFalse(confirm.disabled())
                    assertTrue(confirm.boundsInRoot.right <= width)
                    assertTrue(scene.action("Продолжить").disabled())
                    File(output, "recovery-inspected-$width.png").writeBytes(scene.render(++frame * 16_000_000L).use {
                        it.encodeToData()!!.use { data -> data.bytes }
                    })
                    if (width == 390) {
                        confirm.config[SemanticsActions.OnClick].action!!.invoke()
                        render()
                        assertTrue(store.unsettled("plan").isEmpty())
                        assertNull(store.planFor("plan")!!.issue, "The acknowledged journal notice is no longer actionable")
                        assertEquals(1, nativeCalls, "Confirmation alone does not resume execution")
                        assertFalse(scene.action("Продолжить").disabled())
                        assertTrue(scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.flatMap { it.texts() }
                            .none { "проверьте его перед продолжением" in it })
                        File(output, "recovery-confirmed-$width.png").writeBytes(scene.render(++frame * 16_000_000L).use {
                            it.encodeToData()!!.use { data -> data.bytes }
                        })
                        scene.action("Продолжить").config[SemanticsActions.OnClick].action!!.invoke()
                        render()
                        assertEquals(2, nativeCalls, "Only a fresh explicit Continue admits the next run")
                        assertEquals(sibling, store.planFor(project.id), "Sibling plan must remain unchanged")
                        execution.stop("plan"); runCurrent(); render()
                        scene.action("Проверить предыдущую работу").config[SemanticsActions.OnClick].action!!.invoke(); render()
                        scene.action("Я проверил результат").config[SemanticsActions.OnClick].action!!.invoke(); render()
                        scene.action("Новая цель").config[SemanticsActions.OnClick].action!!.invoke(); render()
                        assertNull(store.planFor("plan"), "New goal deletes only the displayed, explicitly recovered plan")
                        assertEquals(sibling, store.planFor(project.id), "A sibling whose ID equals the project ID must survive")
                        assertEquals(2, nativeCalls)
                    }
                }
            }
            chat.shutdown()
            execution.shutdown()
        } finally { Dispatchers.resetMain() }
    }

    private fun ImageComposeScene.action(label: String): SemanticsNode = semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
        .single { it.config.getOrNull(SemanticsActions.OnClick) != null && label in it.texts() }
    private fun SemanticsNode.disabled() = config.getOrNull(SemanticsProperties.Disabled) != null
    private fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
    private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + children.flatMap { it.texts() }
}
