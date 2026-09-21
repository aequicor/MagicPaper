package io.aequicor.magicpaper.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.aequicor.magicpaper.designsystem.PaperSurface
import io.aequicor.magicpaper.designsystem.PaperTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class MissingSettingsPageRenderTest {
    @Test fun restoredUnavailableSectionHasAVisibleRecoveryAction() {
        val output = File("build/reports/settings-availability").apply { mkdirs() }
        for (scale in listOf(1f, 2f)) {
            var openedOverview = 0
            ImageComposeScene(390, 420) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    PaperTheme { PaperSurface { MissingSettingsPage { openedOverview++ } } }
                }
            }.use { scene ->
                repeat(4) { scene.render(it * 16_000_000L).close() }
                assertEquals(0, openedOverview, "Restoring a route must not navigate or trigger a capability")
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                val nodes = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                assertTrue(nodes.any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Раздел недоступен на этом устройстве" } == true })
                val action = nodes.single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Открыть настройки" } == true }.boundsInRoot
                assertTrue(action.top >= 0 && action.bottom <= 420 && action.left >= 0 && action.right <= 390)
                scene.render(80_000_000L).use { image -> File(output, "saved-section-390-$scale.png").writeBytes(image.encodeToData()!!.use { it.bytes }) }
                scene.sendPointerEvent(PointerEventType.Press, action.center)
                scene.sendPointerEvent(PointerEventType.Release, action.center)
                scene.render(96_000_000L).close()
                assertEquals(1, openedOverview)
            }
        }
    }
}
