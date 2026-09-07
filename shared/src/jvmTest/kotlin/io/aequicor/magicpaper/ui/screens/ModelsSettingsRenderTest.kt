package io.aequicor.magicpaper.ui.screens

import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.aequicor.magicpaper.ui.ModelSettingsFixture
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ModelsSettingsRenderTest {
    @Test fun rendersNarrowDesktopAndRetinaAndExpandsDetails() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val vm = ModelSettingsFixture().prepare()
            val state = vm.state.value
            val output = File("build/reports/models").apply { mkdirs() }
            for ((width, height, density) in listOf(Triple(1000, 800, 1f), Triple(390, 844, 1f), Triple(1800, 1400, 2f))) {
                ImageComposeScene(width, height, density = Density(density)) {
                    MagicPaperTheme { Surface { ModelsSettings(vm, state) } }
                }.use { scene ->
                    repeat(4) { scene.render(it * 16_000_000L).close() }
                    val bytes = scene.render(80_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } }
                    assertTrue(bytes.size > 1000)
                    File(output, "models-${width}x$height.png").writeBytes(bytes)
                    if (density == 1f && width == 1000) {
                        scene.sendPointerEvent(PointerEventType.Press, Offset(170f, 445f))
                        scene.sendPointerEvent(PointerEventType.Release, Offset(170f, 445f))
                        scene.render(96_000_000L).use { File(output, "models-details.png").writeBytes(it.encodeToData()!!.use { data -> data.bytes }) }
                    }
                }
            }
        } finally { Dispatchers.resetMain() }
    }
}
