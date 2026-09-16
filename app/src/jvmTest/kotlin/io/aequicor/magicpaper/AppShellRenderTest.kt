package io.aequicor.magicpaper

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.use
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import io.aequicor.magicpaper.data.storage.InMemoryDurableByteStore
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.data.storage.PersistenceStores
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import io.aequicor.magicpaper.data.storage.persistenceStores
import io.aequicor.magicpaper.di.NavigationSessionConfig
import io.aequicor.magicpaper.di.RuntimeState
import io.aequicor.magicpaper.di.buildRuntime
import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.ProfileBridge
import io.aequicor.magicpaper.domain.SettingsRepository
import io.aequicor.magicpaper.navigation.AppRoute
import io.aequicor.magicpaper.navigation.createAppRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Exercises the production Koin runtime and shell using only isolated memory repositories. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class AppShellRenderTest {
    @Test fun hidingSidebarRetainsTheFeatureAndSelectedArticlesRenderWithDurablePresentation() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val lifecycle = LifecycleRegistry()
        val bridge = object : ProfileBridge {
            override val supportsFilePicker = false
            override suspend fun export(json: String) = false
            override suspend fun import(): String? = null
        }
        val runtime = buildRuntime(InMemoryKeyValueStore(), persistenceStores(InMemoryDurableByteStore()),
            bridge, NavigationSessionConfig())
        try {
            runtime.koin.get<SettingsRepository>().save(AppSettings(onboardingDone = true, paperAnimationEnabled = false))
            runtime.start()
            assertEquals(RuntimeState.Ready, runtime.ready.first { it != RuntimeState.Loading })
            lifecycle.resume()
            val root = createAppRoot(runtime, DefaultComponentContext(lifecycle))
            root.navigate(AppRoute.Docs()); root.awaitIdle()
            val child = root.stack.value.active.instance
            val journal = root.navigationState.value.journal
            ImageComposeScene(1000, 760) { App(runtime, root) }.use { scene ->
                var frame = 0L
                fun draw() { repeat(6) { scene.render(frame++ * 16_000_000L).close() } }
                draw()
                assertTrue(scene.hasText("Документация"))
                assertTrue(scene.hasText("✦ Новый чат"))
                scene.action("Показать или скрыть боковую панель").config[SemanticsActions.OnClick].action!!.invoke()
                draw()
                assertFalse(scene.hasText("✦ Новый чат"))
                assertTrue(scene.hasText("Документация"))
                assertSame(child, root.stack.value.active.instance)
                assertEquals(journal.visits, root.navigationState.value.journal.visits)
                assertEquals(journal.cursor, root.navigationState.value.journal.cursor)
                val directory = File("build/reports/app-shell").apply { mkdirs() }
                scene.render(frame++ * 16_000_000L).use { image ->
                    File(directory, "sidebar-hidden.png").writeBytes(image.encodeToData()!!.use { it.bytes })
                }
                scene.action("Показать или скрыть боковую панель").config[SemanticsActions.OnClick].action!!.invoke()
                draw()
                assertTrue(scene.hasText("✦ Новый чат"))
                assertTrue(scene.hasText("Документация"))
                scene.render(frame * 16_000_000L).use { image ->
                    File(directory, "sidebar-visible.png").writeBytes(image.encodeToData()!!.use { it.bytes })
                }
                // The real per-visit SaveableStateRegistry rejects unsupported lazy
                // keys during composition. Exercise the selected article branch too.
                root.navigate(AppRoute.Docs("request-pins")); root.awaitIdle()
                draw()
                assertTrue(scene.hasText("Закрепления запросов и уточнений (sticky)"))
                root.awaitIdle()
                root.back(); root.awaitIdle(); draw()
                assertEquals(AppRoute.Docs(), root.navigationState.value.route)
                assertTrue(scene.hasText("Документация"))
                root.navigate(AppRoute.Chat()); root.awaitIdle(); draw()
                assertTrue(scene.hasText("MagicPaper"))
                assertTrue(scene.hasText("✦ Новый чат"), "The global session list is visible when a chat opens")
                scene.render(frame * 16_000_000L).use { image ->
                    File(directory, "chat-sidebar-visible.png").writeBytes(image.encodeToData()!!.use { it.bytes })
                }
            }
            root.awaitIdle()
            val persisted = Json.parseToJsonElement(requireNotNull(runtime.koin.get<PersistenceStores>().navigation.load())).jsonObject
            assertTrue("presentationRefs" in persisted)
            assertFalse("presentation" in persisted)
        } finally {
            lifecycle.destroy()
            runtime.close(); runtime.awaitClosed()
            Dispatchers.resetMain()
        }
    }

    private fun ImageComposeScene.nodes() = semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    private fun ImageComposeScene.hasText(text: String) = nodes().any { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    }
    private fun ImageComposeScene.action(description: String) = nodes().first { node ->
        node.config.getOrNull(SemanticsActions.OnClick) != null &&
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
    }
    private fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
}
