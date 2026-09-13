package io.aequicor.magicpaper.tools.paper

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.aequicor.visualization.engine.backend.compose.*
import io.aequicor.visualization.engine.ir.model.*
import io.aequicor.visualization.engine.ir.resolve.*
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class PluginCanvasGeometryTest {
    @Test fun externalRastersFollowCanvasZoomPanClippingAndSiblingOrder() {
        fun raster(color: Color) = ImageBitmap(2, 2).also { Canvas(it).drawRect(Rect(0f, 0f, 2f, 2f), Paint().apply { this.color = color }) }
        val red = raster(Color.Red)
        val blue = raster(Color.Blue)
        val provider = object : ComponentImageProvider {
            override val generation = 1
            override fun resolve(component: ExternalComponent, width: Double, height: Double) = if (component.componentId == "red") red else blue
        }
        fun leaf(id: String, x: Double, width: Double) = DesignNode(
            id, "instance", DesignNodeKind.Instance(id.bindable(), "fake"),
            position = DesignPoint(x, 10.0), size = DesignSize(width, 20.0), sizing = DesignSizing(SizingMode.Fixed, SizingMode.Fixed),
            layoutChild = DesignLayoutChild(absolute = true),
        )
        val root = DesignNode("root", "frame", DesignNodeKind.Frame,
            size = DesignSize(60.0, 60.0), layout = DesignAutoLayout(clipsContent = true),
            children = listOf(leaf("red", 30.0, 50.0), leaf("blue", 45.0, 10.0)))
        val document = DesignDocument(pages = listOf(DesignPage("page", children = listOf(root))))
        ImageComposeScene(180, 160, density = Density(1f)) {
            DesignArtboard(document, "page", Modifier.fillMaxSize(), viewport = CanvasViewport(2f, 10f, 10f),
                componentImages = provider, resolveContext = ResolveContext(externalLibraries = setOf("fake")), interactive = false)
        }.use { scene ->
            val image = scene.render(16_000_000L)
            try {
                val pixels = image.toComposeImageBitmap().toPixelMap()
                assertEquals(Color.Red, pixels[80, 50], "scaled and translated first sibling")
                assertEquals(Color.Blue, pixels[110, 50], "later sibling paints on top")
                assertEquals(0f, pixels[140, 50].alpha, "parent clips overflowing component")
            } finally { image.close() }
        }
    }
}
