package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION")
class LongMessageRenderTest {
    private class Clipboard : ClipboardManager {
        var content: AnnotatedString? = null
        override fun getText() = content
        override fun setText(annotatedString: AnnotatedString) { content = annotatedString }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.texts() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    private fun ImageComposeScene.snapshot(name: String) {
        val dir = File("build/reports/long-messages").apply { mkdirs() }
        File(dir, "$name.png").writeBytes(render(5_000_000_000).use { it.encodeToData()!!.use { data -> data.bytes } })
    }

    @Test fun hugeMessagesKeepTheChatPreviewBoundedAndOpenTheLazyReader() {
        val source = "## Проверка длинного ответа\n\n" + "**Текст сообщения** со [ссылкой](https://example.com). ".repeat(12000)
        runBlocking { ChatMarkdownDocuments.load(source, cache = true) }
        var height = 0
        val clipboard = Clipboard()
        ImageComposeScene(760, 680) {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
            MagicPaperTheme { Surface { Column(Modifier.fillMaxWidth().padding(16.dp).onSizeChanged { height = it.height }) {
                ChatMarkdown(source)
            } } }
            }
        }.use { scene ->
            var frame = 0L
            fun render() { repeat(15) { scene.render(++frame * 32_000_000L).close(); Thread.sleep(5) } }
            render()
            assertTrue(height in 360..430, "The chat should never measure the full message: $height")
            assertTrue(scene.texts().sumOf { it.length } < 8000)
            val button = scene.nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Читать полностью" } == true }
            scene.snapshot("preview")
            val center = button.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, center)
            scene.sendPointerEvent(PointerEventType.Release, center)
            render()
            assertTrue("Сообщение целиком" in scene.texts(), "Open the actual full-message reader")
            assertTrue("Копировать" in scene.texts())
            assertTrue(scene.texts().sumOf { it.length } < 16000, "Opening must not compose the full text")
            scene.snapshot("reader")
            val copy = scene.nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Копировать" } == true }
            scene.sendPointerEvent(PointerEventType.Press, copy.boundsInRoot.center)
            scene.sendPointerEvent(PointerEventType.Release, copy.boundsInRoot.center)
            render()
            assertEquals(source, clipboard.content?.text, "Copy must include the whole original Markdown, including offscreen text")
        }
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
            MagicPaperTheme { Column { ChatPlainText("output 😀 ".repeat(120000)) } }
        }.use { scene ->
            repeat(8) { scene.render(it * 32_000_000L).close(); Thread.sleep(5) }
            assertTrue(scene.texts().all { it.length <= MESSAGE_PREVIEW_CHARS })
            assertTrue("Читать полностью" in scene.texts())
        }
    }
}
