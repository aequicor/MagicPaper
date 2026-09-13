package io.aequicor.magicpaper.tools.paper

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import io.aequicor.visualization.editor.plugins.*
import io.aequicor.visualization.engine.ir.model.PropValue
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.*

class PaperPluginRenderTest {
    @Test fun catalogRendersRealComponentsAndStateMatrix() = runBlocking {
        val plugin = PaperDesignPlugin()
        val registry = DesignSystemPlugins(listOf(plugin))
        val directory = File("build/reports/paper-plugin/renders").apply { mkdirs() }
        directory.listFiles { file -> file.extension == "png" }?.forEach { check(it.delete()) }
        try {
            val cases = plugin.components.map { it.instance("paper") to (it.width to it.height) }.toMutableList()
            for (id in listOf("PaperButton", "PaperField")) {
                val definition = plugin.components.single { it.id == id }
                for (state in definition.variants["state"].orEmpty()) {
                    val value = definition.instance("paper")
                    cases += value.copy(variant = value.variant + ("state" to state)) to (definition.width to definition.height)
                }
            }
            for (id in listOf("PaperField", "PaperWorkspaceComposer", "PaperText")) {
                val value = plugin.components.single { it.id == id }.instance("paper")
                cases += value.copy(props = value.props + ("text" to PropValue.Text(""))) to (240 to 180)
                cases += value.copy(props = value.props + ("text" to PropValue.Text("Очень длинная подпись для проверки узкого окна и увеличенного текста")), variant = value.variant + mapOf("textScale" to "2", "platform" to "Windows")) to (240 to 240)
            }
            for ((index, case) in cases.withIndex()) {
                val (component, size) = case
                assertTrue(registry.errors(component).isEmpty(), component.componentId)
                val raster = plugin.render(ComponentRenderRequest(component, size.first, size.second))
                try {
                    assertEquals(size.first, raster.image.width)
                    assertEquals(size.second, raster.image.height)
                    val pixels = raster.image.toPixelMap()
                    assertTrue((0 until pixels.width step 8).any { x -> (0 until pixels.height step 8).any { y -> pixels[x, y].alpha > 0 } }, component.componentId)
                    val image = Image.makeFromBitmap(raster.image.asSkiaBitmap())
                    try {
                        val data = image.encodeToData()!!
                        try { File(directory, "$index-${component.componentId}.png").writeBytes(data.bytes) } finally { data.close() }
                    } finally { image.close() }
                } finally { raster.dispose() }
            }
        } finally { plugin.close() }
    }

    @Test fun registryRejectsDuplicateIdsAndInvalidTypedValues() {
        val plugin = PaperDesignPlugin()
        try {
            assertFailsWith<IllegalArgumentException> { DesignSystemPlugins(listOf(plugin, plugin)) }
            val registry = DesignSystemPlugins(listOf(plugin))
            val button = plugin.components.single { it.id == "PaperButton" }.instance("paper")
            assertTrue(registry.errors(button.copy(props = mapOf("text" to PropValue.Number(1.0)))).isNotEmpty())
            assertTrue(registry.errors(button.copy(componentId = "Missing")).isNotEmpty())
            assertTrue(registry.errors(button.copy(libraryId = "Missing")).isNotEmpty())
            assertTrue(registry.errors(button.copy(variant = mapOf("state" to "INVENTED"))).isNotEmpty())
        } finally { plugin.close() }
    }
}
