package io.aequicor.magicpaper

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.ui.window.LocalWindowToolbarHeight
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import io.aequicor.magicpaper.data.storage.InMemoryDurableByteStore
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.data.storage.PersistenceStores
import io.aequicor.magicpaper.data.storage.DefaultSettingsConfiguration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import io.aequicor.magicpaper.data.storage.persistenceStores
import io.aequicor.magicpaper.di.NavigationSessionConfig
import io.aequicor.magicpaper.di.RuntimeState
import io.aequicor.magicpaper.di.buildRuntime
import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.ProfileBridge
import io.aequicor.magicpaper.navigation.AppRoute
import io.aequicor.magicpaper.navigation.DialogRoute
import io.aequicor.magicpaper.navigation.RootDialogLifecycle
import io.aequicor.magicpaper.navigation.createAppRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.awt.EventQueue
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
            runtime.koin.get<DefaultSettingsConfiguration>().changeSettings(AppSettings(onboardingDone = true, paperAnimationEnabled = false)).getOrThrow()
            runtime.start()
            assertEquals(RuntimeState.Ready, runtime.ready.first { it != RuntimeState.Loading })
            lifecycle.resume()
            val root = createAppRoot(runtime, DefaultComponentContext(lifecycle))
            root.navigate(AppRoute.Docs()); root.awaitIdle()
            val child = root.stack.value.active.instance
            val journal = root.navigationState.value.journal
            val toolbarHeight = mutableStateOf(40.dp)
            val compact = mutableStateOf(false)
            val scene = onUi { ImageComposeScene(1000, 760) {
                CompositionLocalProvider(LocalWindowToolbarHeight provides toolbarHeight.value) { App(runtime, root, compact = compact.value) }
            } }
            try {
                var frame = 0L
                fun draw() { repeat(6) { onUi { scene.render(frame++ * 16_000_000L).close() } } }
                draw()
                assertTrue(scene.hasText("Документация"))
                assertTrue(scene.hasAction("Настройки"))
                assertFalse(scene.hasAction("Назад"))
                assertFalse(scene.hasAction("Вперёд"))
                assertFalse(scene.hasText("Справочник"))
                val normalTop = scene.text("Документация").boundsInRoot.top
                onUi { toolbarHeight.value = 0.dp }; draw()
                assertFalse(scene.hasText("MagicPaper"))
                assertFalse(scene.hasAction("Настройки"))
                assertFalse(scene.hasAction("Расходы"))
                assertFalse(scene.hasAction("Скрыть список сессий"))
                assertEquals(normalTop - 40f, scene.text("Документация").boundsInRoot.top, .5f)
                assertSame(child, root.stack.value.active.instance)
                val fullscreenDirectory = File("build/reports/app-shell").apply { mkdirs() }
                onUi { scene.render(frame++ * 16_000_000L).use { image ->
                    File(fullscreenDirectory, "fullscreen.png").writeBytes(image.encodeToData()!!.use { it.bytes })
                } }
                onUi { toolbarHeight.value = 40.dp }; draw()
                assertTrue(scene.hasText("MagicPaper"))
                assertTrue(scene.hasAction("Настройки"))
                assertEquals(normalTop, scene.text("Документация").boundsInRoot.top, .5f)
                assertTrue(scene.hasAction("Новый чат"))
                onUi { compact.value = true }; draw()
                assertFalse(scene.hasAction("Новый чат"))
                assertSame(child, root.stack.value.active.instance)
                onUi { compact.value = false }; draw()
                assertTrue(scene.hasAction("Новый чат"), "Compact mode must preserve the normal sidebar preference")
                onUi { scene.action("Скрыть список сессий").config[SemanticsActions.OnClick].action!!.invoke() }
                draw()
                assertFalse(scene.hasAction("Новый чат"))
                assertTrue(scene.hasText("Документация"))
                assertSame(child, root.stack.value.active.instance)
                assertEquals(journal.visits, root.navigationState.value.journal.visits)
                assertEquals(journal.cursor, root.navigationState.value.journal.cursor)
                val directory = File("build/reports/app-shell").apply { mkdirs() }
                onUi { scene.render(frame++ * 16_000_000L).use { image ->
                    File(directory, "sidebar-hidden.png").writeBytes(image.encodeToData()!!.use { it.bytes })
                } }
                onUi { scene.action("Показать список сессий").config[SemanticsActions.OnClick].action!!.invoke() }
                draw()
                assertTrue(scene.hasAction("Новый чат"))
                assertTrue(scene.hasText("Документация"))
                onUi { scene.render(frame * 16_000_000L).use { image ->
                    File(directory, "sidebar-visible.png").writeBytes(image.encodeToData()!!.use { it.bytes })
                } }
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
                val titleLayouts = mutableListOf<TextLayoutResult>()
                onUi { scene.text("MagicPaper").config[SemanticsActions.GetTextLayoutResult].action!!.invoke(titleLayouts) }
                assertEquals(13.sp, titleLayouts.single().layoutInput.style.fontSize)
                assertEquals(FontFamily.SansSerif, titleLayouts.single().layoutInput.style.fontFamily)
                assertTrue(scene.hasAction("Новый чат"), "The global session list is visible when a chat opens")
                onUi { scene.action("Скрыть список сессий").config[SemanticsActions.OnClick].action!!.invoke() }
                draw()
                assertFalse(scene.hasAction("Новый чат"))
                root.navigate(AppRoute.Docs()); root.awaitIdle(); draw()
                root.navigate(AppRoute.Chat()); root.awaitIdle(); draw()
                assertFalse(scene.hasAction("Новый чат"), "Chat shell configuration survives switching visits")
                onUi { scene.render(frame * 16_000_000L).use { image ->
                    File(directory, "chat-sidebar-visible.png").writeBytes(image.encodeToData()!!.use { it.bytes })
                } }
                val chatChild = root.stack.value.active.instance
                onUi { compact.value = true; scene.constraints = androidx.compose.ui.unit.Constraints.fixed(480, 640) }; draw()
                assertSame(chatChild, root.stack.value.active.instance)
                assertFalse(scene.hasAction("Новый чат"))
                assertTrue(scene.hasText("MagicPaper"))
                onUi { scene.render(frame * 16_000_000L).use { image ->
                    val pixels = javax.imageio.ImageIO.read(image.encodeToData()!!.use { it.bytes }.inputStream())
                    javax.imageio.ImageIO.write(pixels.getSubimage(0, 0, 480, 640), "png", File(directory, "computer-compact-chat.png"))
                    pixels.flush()
                } }
                // A feature modal is drawn by its owner; the slot entry must not add the shell fallback.
                root.showDialog(DialogRoute(RootDialogLifecycle.FEATURE_MODAL_KIND, "editor")); root.awaitIdle(); draw()
                assertFalse(scene.hasText("Раздел недоступен"), "A feature modal must not raise the unavailable-section dialog")
                root.dismissDialog(); root.awaitIdle()
                root.showDialog(DialogRoute("unregistered-kind")); root.awaitIdle(); draw()
                assertTrue(scene.hasText("Раздел недоступен"), "An unknown dialog kind keeps the fallback")
                root.dismissDialog(); root.awaitIdle()
            } finally { onUi { scene.close() } }
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

    // Compose's render dispatcher and AWT event loop must share the UI thread;
    // rendering on the JUnit worker can deadlock snapshot observers with live flows.
    private fun <T> onUi(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun ImageComposeScene.nodes() = onUi { semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) } }
    private fun ImageComposeScene.hasAction(label: String) = nodes().any {
        it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label)
    }
    private fun ImageComposeScene.hasText(text: String) = nodes().any { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    }
    private fun ImageComposeScene.text(text: String) = nodes().first { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    }
    private fun ImageComposeScene.action(description: String) = nodes().first { node ->
        node.config.getOrNull(SemanticsActions.OnClick) != null &&
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
    }
    private fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
}
