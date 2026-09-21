package io.aequicor.magicpaper.ui.screens

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.aequicor.magicpaper.data.coding.backendCatalog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class NativeEnginesSettingsRenderTest {
    @Test fun descriptorControlsRenderAtDesktopNarrowAndLargeTextSizes() {
        val output = File("build/reports/native-engines").apply { mkdirs() }
        for ((width, scale) in listOf(1000 to 1f, 390 to 1f, 390 to 2f)) {
            ImageComposeScene(width, 1600, density = Density(1f, scale)) { NativeEnginesSettingsPreview() }.use { scene ->
                repeat(6) { scene.render(it * 16_000_000L).close() }
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                val nodes = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                val text = nodes.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }
                backendCatalog.descriptors.forEach { descriptor ->
                    assertTrue(descriptor.adapterName in text)
                    assertTrue(descriptor.summary in text)
                    assertTrue(descriptor.providerSummary in text)
                }
                scene.render(120_000_000L).use { image -> File(output, "controls-$width-$scale.png").writeBytes(image.encodeToData()!!.use { it.bytes }) }
            }
        }
    }
}
