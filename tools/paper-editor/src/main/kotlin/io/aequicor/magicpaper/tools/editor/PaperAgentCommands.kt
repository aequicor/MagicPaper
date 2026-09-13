package io.aequicor.magicpaper.tools.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.use
import io.aequicor.magicpaper.tools.paper.PaperDesignPlugin
import io.aequicor.visualization.editor.domain.editorSlmCompileOptions
import io.aequicor.visualization.editor.plugins.*
import io.aequicor.visualization.engine.backend.compose.*
import io.aequicor.visualization.engine.frontend.compileSlm
import io.aequicor.visualization.engine.ir.layout.DesignLayoutEngine
import io.aequicor.visualization.engine.ir.model.*
import io.aequicor.visualization.engine.ir.resolve.*
import io.aequicor.visualization.engine.ir.validate.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ceil

/** Local batch protocol used by the chat adapter. No HTTP listener or provider credentials. */
internal suspend fun paperAgentCommand(command: String, input: Path?, output: Path?): JsonObject {
    val plugin = PaperDesignPlugin()
    try {
        val registry = DesignSystemPlugins(listOf(plugin))
        if (command == "catalog") return buildJsonObject {
            put("protocol", 1)
            put("components", buildJsonArray {
                plugin.components.forEach { c -> add(buildJsonObject {
                    put("id", c.id); put("group", c.group); put("width", c.width); put("height", c.height)
                    put("props", buildJsonObject { c.properties.forEach { p -> put(p.id, buildJsonObject {
                        put("type", p.type.name)
                        put("default", when (val v = p.default) { is PropValue.Text -> v.value; is PropValue.Bool -> v.value.toString(); is PropValue.Number -> v.value.toString(); else -> error("Unsupported catalogue default") })
                    }) } })
                    put("variants", buildJsonObject { c.variants.forEach { (key, values) -> put(key, buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }) } })
                }) }
            })
        }
        require(command == "render") { "Unknown agent command" }
        val path = requireNotNull(input)
        require(Files.size(path) <= 512 * 1024) { "Layout source exceeds 512 KiB" }
        val compiled = compileSlm(Files.readString(path), editorSlmCompileOptions("screen.layout.md"))
        val document = compiled.document
        val context = ResolveContext(externalLibraries = registry.libraryIds)
        val errors = compiled.diagnostics.filter { it.severity == DesignSeverity.Error }.map { it.message }.toMutableList()
        if (document != null) {
            errors += validateDesignDocument(document, context).filter { it.severity == DesignSeverity.Error }.map { it.message }
            document.pages.flatMap { it.children }.forEach { root ->
                fun inspect(n: DesignNode) {
                    (n.kind as? DesignNodeKind.Instance)?.let { instance ->
                        errors += registry.errors(ExternalComponent(instance.libraryRef, instance.componentId.literalOrNull().orEmpty(), instance.props, instance.variant))
                    }
                    n.children.forEach(::inspect)
                }
                inspect(root)
            }
        }
        val page = document?.pages?.singleOrNull()
        val root = page?.children?.singleOrNull()
        if (root == null) errors += "Create exactly one page with one root Frame."
        if (errors.isNotEmpty() || document == null || page == null || root == null) return buildJsonObject {
            put("valid", false); put("diagnostics", errors.distinct().take(20).joinToString("\n").take(8000))
        }
        val resolved = requireNotNull(DesignResolver(document, context).resolveNodeTree(root))
        val layout = DesignLayoutEngine().layout(resolved)
        val width = ceil(layout.width).toInt(); val height = ceil(layout.height).toInt()
        require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 4_194_304) { "Layout exceeds 4096 px / 4 MP" }
        val session = PluginRenderSession(registry)
        try {
            session.prepare(PluginRenderSession.requests(layout))
            require(session.errors.isEmpty()) { "A Paper component could not be rendered" }
            require(layout.allBoxes().all { it.node.externalComponent == null || PluginRenderSession.request(it.node.externalComponent!!, it.width, it.height) != null }) { "Component size exceeds render limits" }
            val png = withContext(Dispatchers.Swing) { renderPaperDocument(document, page.id, width, height, context, session) }
            Files.write(requireNotNull(output), png)
            return buildJsonObject { put("valid", true); put("width", width); put("height", height); put("diagnostics", "") }
        } finally { session.close() }
    } finally { plugin.close() }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun renderPaperDocument(document: DesignDocument, pageId: String, width: Int, height: Int,
    context: ResolveContext, images: ComponentImageProvider): ByteArray =
    ImageComposeScene(width, height) {
        DesignArtboard(document, pageId, Modifier.fillMaxSize(), viewport = CanvasViewport(1f, 0f, 0f),
            interactive = false, showSelection = false, resolveContext = context, componentImages = images)
    }.use { scene ->
        repeat(3) { scene.render((it + 1) * 16_000_000L).close() }
        scene.render(64_000_000L).use { image -> image.encodeToData()!!.use { it.bytes } }
    }
