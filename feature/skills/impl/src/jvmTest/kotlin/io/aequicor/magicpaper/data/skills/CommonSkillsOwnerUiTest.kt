package io.aequicor.magicpaper.data.skills

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.plugins.builtin.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class CommonSkillsOwnerUiTest {
    @Test fun defaultEmptyAndLoadingRenderWithoutStartingProviderWork() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val output = Files.createDirectories(Path.of("build/reports/common-skills"))
            var thrown: Throwable? = null
            EventQueue.invokeAndWait {
                try {
                    for (education in listOf(false, true)) for (state in listOf(
                        CommonSkillsPreviewState.DEFAULT, CommonSkillsPreviewState.EMPTY, CommonSkillsPreviewState.LOADING)) {
                        val plugin = commonSkillsPreviewPlugin(education, state)
                        ImageComposeScene(440, 700) {
                            PaperTheme { PaperSurface(Modifier.fillMaxSize().padding(LocalPaperSpacing.current.md)) { plugin.Content() } }
                        }.use { scene ->
                            repeat(4) { scene.render(it * 16_000_000L).close() }
                            val nodes = scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                            val text = nodes.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                            when (state) {
                                CommonSkillsPreviewState.DEFAULT -> assertTrue("Проверка последовательности действий" in text)
                                CommonSkillsPreviewState.EMPTY -> assertTrue(text.any { it.startsWith("Пока ") })
                                CommonSkillsPreviewState.LOADING -> {
                                    assertTrue("Загрузка навыков…" in text)
                                    assertFalse(text.any { it.startsWith("Пока ") })
                                }
                                else -> error("Unselected fixture")
                            }
                            scene.render(80_000_000L).use { image -> image.encodeToData()!!.use {
                                Files.write(output.resolve("${if (education) "education" else "catalog"}-${state.name.lowercase()}.png"), it.bytes)
                            } }
                        }
                    }
                } catch (failure: Throwable) { thrown = failure }
            }
            thrown?.let { throw it }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun failureKeepsLibraryVisibleAndRequiresExplicitReloadBeforeMutations() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val output = Files.createDirectories(Path.of("build/reports/common-skills"))
            var thrown: Throwable? = null
            EventQueue.invokeAndWait {
                try {
                    for (education in listOf(false, true)) {
                        for ((width, fontScale) in listOf(360 to 1f, 480 to 1.6f, 900 to 1f)) {
                            val plugin = commonSkillsPreviewPlugin(education, CommonSkillsPreviewState.ERROR)
                            ImageComposeScene(width, 1100) {
                                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                                    PaperTheme { PaperSurface(Modifier.fillMaxSize().padding(LocalPaperSpacing.current.md)) { plugin.Content() } }
                                }
                            }.use { scene ->
                                var frame = 0L
                                fun render() { scene.render(frame++ * 16_000_000L).close() }
                                repeat(4) { render() }
                                fun nodes() = scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                                fun text(node: SemanticsNode, value: String) = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
                                assertTrue(nodes().any { text(it, "Проверка последовательности действий") })
                                val toggles = nodes().filter { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }
                                assertTrue(toggles.isNotEmpty())
                                assertTrue(toggles.all { it.config.contains(SemanticsProperties.Disabled) })
                                scene.render(frame++ * 16_000_000L).use { image -> image.encodeToData()!!.use {
                                    Files.write(output.resolve("${if (education) "education" else "catalog"}-$width-$fontScale.png"), it.bytes)
                                } }
                                val reload = nodes().single { text(it, "Обновить библиотеку") && it.config.getOrNull(SemanticsActions.OnClick) != null }
                                assertEquals(true, reload.config[SemanticsActions.OnClick].action?.invoke())
                                repeat(4) { render() }
                                assertFalse(nodes().any { text(it, "Обновить библиотеку") })
                                assertTrue(nodes().filter { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }
                                    .none { it.config.contains(SemanticsProperties.Disabled) })
                            }
                        }
                    }
                } catch (failure: Throwable) { thrown = failure }
            }
            thrown?.let { throw it }
        } finally { Dispatchers.resetMain() }
    }

    private fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
}
