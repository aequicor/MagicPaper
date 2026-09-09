package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class SearchSettingsRenderTest {
    @Test fun rendersProvidersAtDesktopAndPhoneWidths() {
        val output = File("build/reports/search-settings").apply { mkdirs() }
        for (provider in SearchProvider.entries) {
            for (width in listOf(390, 1100)) {
                var checks = 0
                ImageComposeScene(width, 1100) {
                    var draft by remember { mutableStateOf(AppSettings(searchProvider = provider,
                        queritApiKey = "test-key", queritContentApiKey = "content-key", queritContentEnabled = true)) }
                    MagicPaperTheme { Surface {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            SearchApiSettings(draft, { _, _ -> checks++; SearchConnectionResult(true, "Подключено · поиск работает") }) { draft = it }
                        }
                    } }
                }.use { scene ->
                    repeat(4) { scene.render(it * 16_000_000L).close() }
                    val bytes = scene.render(80_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } }
                    assertTrue(bytes.size > 1000)
                    File(output, "${provider.name.lowercase()}-$width.png").writeBytes(bytes)
                    if (provider == SearchProvider.QUERIT && width == 390) {
                        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                        val control = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.first {
                            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Проверить подключение" } == true
                        }.boundsInRoot.center
                        scene.sendPointerEvent(PointerEventType.Press, control)
                        scene.sendPointerEvent(PointerEventType.Release, control)
                        repeat(4) { scene.render(100_000_000L + it * 16_000_000L).close() }
                        assertEquals(1, checks, "Connection button must invoke the checker")
                        scene.render(180_000_000L).use {
                            File(output, "querit-checked-390.png").writeBytes(it.encodeToData()!!.use { data -> data.bytes })
                        }
                    }
                }
            }
        }
    }
}
