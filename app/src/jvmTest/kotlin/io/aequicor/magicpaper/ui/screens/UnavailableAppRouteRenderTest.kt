package io.aequicor.magicpaper.ui.screens

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.designsystem.PaperSurface
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.di.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.navigation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import java.io.File
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class UnavailableAppRouteRenderTest {
    @Test fun savedProjectLinkRemainsRecoverableWithoutAnyNativeRegistration() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val lifecycle = LifecycleRegistry()
        val runtime = buildRuntime(InMemoryKeyValueStore(), persistenceStores(InMemoryDurableByteStore()),
            object : ProfileBridge {
                override val supportsFilePicker = false
                override suspend fun export(json: String) = false
                override suspend fun import(): String? = null
            }, NavigationSessionConfig(initialDeepLink = "magicpaper://projects/project/sessions/session"))
        try {
            runtime.koin.get<DefaultSettingsConfiguration>().changeSettings(AppSettings(onboardingDone = true)).getOrThrow()
            runtime.start()
            assertEquals(RuntimeState.Ready, runtime.ready.first { it != RuntimeState.Loading })
            val root = createAppRoot(runtime, DefaultComponentContext(lifecycle))
            root.awaitIdle(); runCurrent()
            assertEquals(AppRoute.Projects("project", "session"), root.navigationState.value.route)
            val child = root.stack.value.active.instance
            val output = File("build/reports/platform-sections").apply { mkdirs() }
            for (scale in listOf(1f, 2f)) {
                ImageComposeScene(390, 520, density = Density(1f, scale)) { PaperTheme { PaperSurface { child.Content() } } }.use { scene ->
                    repeat(6) { scene.render(it * 16_000_000L).close() }
                    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                    val nodes = scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                    val recovery = nodes.single { node ->
                        node.config.getOrNull(SemanticsActions.OnClick) != null &&
                            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Открыть чат" }
                    }
                    assertTrue(recovery.boundsInRoot.left >= 0 && recovery.boundsInRoot.right <= 390 && recovery.boundsInRoot.bottom <= 520)
                    assertEquals(AppRoute.Projects("project", "session"), root.navigationState.value.route)
                    scene.render(120_000_000L).use { image -> File(output, "saved-project-390-$scale.png").writeBytes(image.encodeToData()!!.use { it.bytes }) }
                    if (scale == 2f) assertTrue(recovery.config[SemanticsActions.OnClick].action!!.invoke())
                }
            }
            root.awaitIdle()
            assertEquals(AppRoute.Chat(), root.navigationState.value.route)
        } finally { lifecycle.destroy(); runtime.close(); runtime.awaitClosed(); Dispatchers.resetMain() }
    }
}
