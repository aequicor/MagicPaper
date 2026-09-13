package io.aequicor.magicpaper.ui.components
import androidx.compose.foundation.lazy.LazyListState
import java.awt.EventQueue


import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.aequicor.magicpaper.designsystem.PaperTheme as MagicPaperTheme
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class LongMessageRenderTest {
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.texts() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    private fun ImageComposeScene.snapshot(name: String) {
        val dir = File("build/reports/long-messages").apply { mkdirs() }
        File(dir, "$name.png").writeBytes(render(5_000_000_000).use { it.encodeToData()!!.use { data -> data.bytes } })
    }

    private fun <T> onUi(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }









    @Test fun oversizedCodeListTableAndPlainParagraphHaveBoundedLazyLayout() {
        val examples = linkedMapOf(
            "code" to "```kotlin\n" + (1..5000).joinToString("\n") { "println(\"Строка $it\")" } + "\n```",
            "list" to (1..5000).joinToString("\n") { "$it. Элемент **$it** со [ссылкой](https://example.com)" },
            "table" to "| Название | Значение |\n| :--- | ---: |\n" + (1..2000).joinToString("\n") { "| Строка $it | **$it** |" },
            "paragraph" to "Длинный абзац **с форматированием**. ".repeat(15000),
        )
        for ((name, source) in examples) {
            val document = runBlocking { ChatMarkdownDocuments.load(source, cache = false) }
            ImageComposeScene(760, 680) {
                MagicPaperTheme { Surface { MarkdownDocumentBody(document, document.blocks, Modifier.fillMaxSize().padding(16.dp), lazy = true) } }
            }.use { scene ->
                var frame = 0L
                fun render() { repeat(8) { scene.render(++frame * 32_000_000L).close(); Thread.sleep(5) } }
                render()
                assertTrue(scene.texts().isNotEmpty(), name)
                assertTrue(scene.texts().sumOf { it.length } < 12000, "$name must only lay out visible fragments")
                scene.snapshot(name)
                repeat(3) {
                    scene.sendPointerEvent(PointerEventType.Scroll, Offset(300f, 400f), scrollDelta = Offset(0f, 6f))
                    render()
                    assertTrue(scene.texts().sumOf { it.length } < 12000, "$name became eager while scrolling")
                }
            }
        }
    }

    @Test fun megabytePlainTextPreviewStaysBounded() {
        ImageComposeScene(760, 680) {
            MagicPaperTheme { Column { PaperChatPlainText("output 😀 ".repeat(120000)) } }
        }.use { scene ->
            repeat(8) { scene.render(it * 32_000_000L).close(); Thread.sleep(5) }
            assertTrue(scene.texts().all { it.length <= MESSAGE_PREVIEW_CHARS })
            assertTrue("Читать далее" in scene.texts())
        }
    }
}
