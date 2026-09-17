package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class PaperComputerFeedbackTest {
    @Test fun compactControlsRenderAndStopRemainsReachableAtLargeText() {
        for (scale in listOf(1f, 1.5f)) {
            var stopped = false
            val scene = onPaperUi { ImageComposeScene(480, 360) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) { PaperTheme { PaperSurface {
                    Box(Modifier.fillMaxSize()) {
                        PaperComputerFeedbackFrame(0.8f, Offset(130f, 180f), true, Modifier.fillMaxSize())
                        PaperComputerControlBar("Нажатие мышью · 1600 × 900", { stopped = true })
                    }
                } } }
            } }
            try {
                onPaperUi {
                    repeat(8) { scene.render(it * 16_000_000L).close() }
                    val nodes = scene.semanticsOwners.flatMap { it.rootSemanticsNode.flatten() }
                    val stop = nodes.single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Остановить") == true }
                    assertTrue(stop.boundsInRoot.right <= 480 && stop.boundsInRoot.top >= 0)
                    stop.config[SemanticsActions.OnClick].action!!.invoke()
                    assertTrue(stopped)
                    File("build/reports/computer-use/controls-$scale.png").apply { parentFile.mkdirs() }
                        .writeBytes(scene.render().use { it.encodeToData()!!.use { data -> data.bytes } })
                }
            } finally { onPaperUi { scene.close() } }
        }
    }
    private fun SemanticsNode.flatten(): List<SemanticsNode> = listOf(this) + children.flatMap { it.flatten() }
}
