package io.aequicor.magicpaper.tools.paper

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp
import io.aequicor.visualization.MissionEditorStateHolder
import io.aequicor.visualization.editor.domain.*
import io.aequicor.visualization.editor.plugins.*
import io.aequicor.visualization.editor.presentation.DesignEditorIntent
import io.aequicor.visualization.editor.ui.*
import io.aequicor.visualization.editor.ui.theme.EditorTheme
import io.aequicor.visualization.engine.ir.model.DesignNodeKind
import io.aequicor.visualization.engine.ir.resolve.*
import io.aequicor.visualization.engine.ir.layout.DesignLayoutEngine
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class PaperWorkbenchRenderTest {
    @Test fun actualEditorPanesRenderWithPaperInstancesAtWideAndNarrowSizes() {
        val plugin = PaperDesignPlugin()
        val registry = DesignSystemPlugins(listOf(plugin))
        val source = File("../paper-editor/examples/paper-workspace.layout.md").readText()
        val state = MissionEditorStateHolder(LoadDesignDocumentUseCase(object : DesignDocumentRepository {
            override fun missionDocumentSources() = listOf(MissionDocumentSource("paper-workspace.layout.md", source))
        }), designPlugins = registry)
        assertNotNull(state.designState.document, state.designState.diagnostics.joinToString { it.message })
        assertTrue(state.designState.document!!.nodeById("paper-button")?.kind is DesignNodeKind.Instance)
        state.dispatch(DesignEditorIntent.SelectNode("paper-button"))
        val session = PluginRenderSession(registry)
        val document = state.designState.document!!
        val resolved = DesignResolver(document, ResolveContext(externalLibraries = registry.libraryIds)).resolveNodeTree(document.pages.first().children.first())!!
        val layout = DesignLayoutEngine().layout(resolved)
        runBlocking { session.prepare(PluginRenderSession.requests(layout)) }
        assertTrue(session.errors.isEmpty(), session.errors.toString())
        try {
            for (width in listOf(1440, 720)) {
                ImageComposeScene(width, 960) {
                    EditorTheme {
                        CompositionLocalProvider(LocalPluginRenderSession provides session) {
                            if (width < 1100) EditorPluginNarrowLayout(state)
                            else Row(Modifier.fillMaxSize()) {
                                EditorPluginSourcePane(state, Modifier.width(280.dp).fillMaxHeight())
                                EditorCanvasPane(state, Modifier.weight(1f).fillMaxHeight())
                                EditorPluginInspectorPane(state, Modifier.width(320.dp).fillMaxHeight())
                            }
                        }
                    }
                }.use { scene ->
                    repeat(5) { scene.render((it + 1) * 16_000_000L).close() }
                    // All component rasters were awaited before composing the real editor panels.
                    val image = scene.render(128_000_000L)
                    try {
                        val data = image.encodeToData()!!
                        try {
                            File("build/reports/paper-plugin/workbench-$width.png").apply { parentFile.mkdirs(); writeBytes(data.bytes) }
                        } finally { data.close() }
                    } finally { image.close() }
                }
            }
        } finally { session.close(); plugin.close() }
    }
}
