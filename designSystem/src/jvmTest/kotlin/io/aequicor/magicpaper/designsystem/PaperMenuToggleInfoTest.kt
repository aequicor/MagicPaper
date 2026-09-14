package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class PaperMenuToggleInfoTest {
    @Test fun disabledSettingRetainsIndependentInformationActionAtNarrowAndLargeTextSizes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (scale in listOf(1f, 1.5f)) {
                var changes = 0
                ImageComposeScene(360, 350) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                        PaperTheme { PaperSurface { Column {
                            PaperMenuToggleInfo("Worktree", true, true, "Работа в отдельной Git-копии", { changes++ })
                            PaperMenuToggleInfo("Worktree", false, true, "Режим выключен", { changes++ })
                            PaperMenuToggleInfo("Worktree", true, false, "Режим можно изменить после завершения текущей задачи", { changes++ })
                            PaperMenuToggleInfo("Worktree", false, false, "В папке нет Git-репозитория", { changes++ })
                        } } }
                    }
                }.use { scene ->
                    var frame = 0L
                    fun render() { repeat(4) { frame += 16_000_000L; scene.render(frame).close(); runCurrent() } }
                    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                    fun nodes() = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                    render()
                    val toggles = nodes().filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox }
                    assertEquals(4, toggles.size)
                    assertFalse(toggles[0].config.contains(SemanticsProperties.Disabled))
                    assertTrue(toggles[2].config.contains(SemanticsProperties.Disabled))
                    toggles[0].config[SemanticsActions.OnClick].action!!.invoke()
                    assertEquals(1, changes)
                    val info = nodes().filter { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Worktree: информация") }
                    assertEquals(4, info.size)
                    assertTrue(info.none { it.config.contains(SemanticsProperties.Disabled) })
                    assertTrue(info.all { it.boundsInRoot.right <= 360 })
                    val output = File("build/reports/worktree-menu").apply { mkdirs() }
                    File(output, "states-$scale.png").writeBytes(scene.render(frame + 16_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })
                    info[2].config[SemanticsActions.OnClick].action!!.invoke()
                    runCurrent(); repeat(12) { advanceTimeBy(16); render() }
                    assertEquals(1, changes, "Information must not toggle a disabled setting")
                    File(output, "information-$scale.png").writeBytes(scene.render(frame + 16_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })
                }
            }
        } finally { Dispatchers.resetMain() }
    }
}
