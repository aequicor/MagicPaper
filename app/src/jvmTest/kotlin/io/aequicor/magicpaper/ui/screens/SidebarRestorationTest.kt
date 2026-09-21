package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.use
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.di.*
import io.aequicor.magicpaper.domain.ProfileBridge
import io.aequicor.magicpaper.navigation.VisitPresentationState
import io.aequicor.magicpaper.ui.*
import java.awt.EventQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class SidebarRestorationTest {
    @Test fun legacyCollapseMapsDoNotBecomeSearchOrArchiveFlags() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val runtime = runtime()
        try { onUi {
            var snapshot: String? = null
            val actions = SidebarActions(runtime.koin.get<ChatService>(), emptyList())
            val recency = SessionRecencyTracker { 0L }
            // Legacy consecutive rememberSaveable calls shared one positional key.
            // Before search existed, that key contained the two collapse maps.
            val legacy = """{"-5coq4l9ubr1a":[["state",["structural",["map",[]]]],["state",["structural",["map",[]]]]]}"""
            val owner = VisitPresentationState(legacy) { snapshot = it }
            scene(owner, actions, recency).use { scene ->
                scene.draw()
                scene.click("Поиск сессий")
                scene.draw()
                scene.click("Архив: 0")
                scene.draw()
                owner.flush()
            }
            val restarted = VisitPresentationState(requireNotNull(snapshot)) { snapshot = it }
            scene(restarted, actions, recency).use { scene ->
                scene.draw()
                assertNotNull(scene.action("Закрыть поиск"))
                assertNotNull(scene.action("Вернуться ко всем сессиям"))
            }
        } } finally {
            runtime.close(); runtime.awaitClosed()
            Dispatchers.resetMain()
        }
    }

    @Test fun everyStatusFilterSurvivesPresentationRestore() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val runtime = runtime()
        try { onUi {
            val actions = SidebarActions(runtime.koin.get<ChatService>(), emptyList())
            val recency = SessionRecencyTracker { 0L }
            for (filter in SidebarStatusFilter.entries) {
                var snapshot: String? = null
                val errors = mutableListOf<String>()
                val owner = VisitPresentationState(null, onError = { errors += it }) { snapshot = it }
                scene(owner, actions, recency).use { scene ->
                    scene.draw()
                    scene.click("Фильтры сессий")
                    scene.draw()
                    val label = if (filter == SidebarStatusFilter.ALL) "✓ ${filter.label}" else filter.label
                    val option = scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }.first {
                        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true &&
                            it.config.getOrNull(SemanticsActions.OnClick) != null
                    }
                    assertTrue(option.config[SemanticsActions.OnClick].action!!.invoke())
                    scene.draw()
                    owner.flush()
                }
                val restarted = VisitPresentationState(requireNotNull(snapshot), onError = { errors += it }) { snapshot = it }
                scene(restarted, actions, recency).use { scene ->
                    scene.draw()
                    scene.click("Фильтры сессий")
                    scene.draw()
                    assertTrue(scene.nodes().any {
                        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "✓ ${filter.label}" } == true
                    }, "Restored filter: $filter")
                }
                assertTrue(errors.isEmpty(), errors.joinToString())
            }
        } } finally {
            runtime.close(); runtime.awaitClosed()
            Dispatchers.resetMain()
        }
    }

    private fun runtime() = buildRuntime(InMemoryKeyValueStore(), persistenceStores(InMemoryDurableByteStore()),
        object : ProfileBridge {
            override val supportsFilePicker = false
            override suspend fun export(json: String) = false
            override suspend fun import(): String? = null
        }, NavigationSessionConfig())

    private fun scene(owner: VisitPresentationState, actions: SidebarActions, recency: SessionRecencyTracker) =
        ImageComposeScene(320, 720) {
            PaperTheme {
                owner.Content {
                    UnifiedSidebar(actions, emptyList(), listOf(SidebarProjection(statusFilters = SidebarStatusFilter.entries)), null, false,
                        listState = LazyListState())
                }
            }
        }
    private fun ImageComposeScene.draw() = repeat(6) { render(it * 16_000_000L).close() }
    private fun ImageComposeScene.action(description: String) = nodes().firstOrNull {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true &&
            it.config.getOrNull(SemanticsActions.OnClick) != null
    }
    private fun ImageComposeScene.click(description: String) {
        assertTrue(requireNotNull(action(description)).config[SemanticsActions.OnClick].action!!.invoke())
    }
    private fun ImageComposeScene.nodes() = semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    private fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)

    // Scene lifecycle and snapshot notifications must share the AWT thread. Otherwise
    // an apply observer can hold the snapshot lock while the test holds the scene lock.
    private fun <T> onUi(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
