package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.ui.components.ChatMarkdownDocuments
import io.aequicor.magicpaper.ui.components.PaperChatMarkdown
import io.aequicor.magicpaper.ui.components.PaperInlineMessageParts
import io.aequicor.magicpaper.ui.components.PaperMessageSelectionContainer
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class PaperMarkdownSelectionTest {
    @Test fun shortReadingMessageKeepsItsParagraphsInOneSelectableItem() {
        val source = "Первый абзац.\n\nВторой абзац.\n\nТретий абзац."
        val document = runBlocking { ChatMarkdownDocuments.load(source, cache = false) }
        val parts = PaperInlineMessageParts(source, document, emptyList())

        assertTrue(document.inlineBlocks.size > 1)
        assertEquals(1, parts.size)
    }

    @Test fun longReadingMessageStaysFragmentedForLazyComposition() {
        val source = (1..100).joinToString("\n\n") { "Абзац $it: " + "текст ".repeat(12) }
        val document = runBlocking { ChatMarkdownDocuments.load(source, cache = false) }
        val parts = PaperInlineMessageParts(source, document, emptyList())

        assertTrue(parts.size > 1)
        assertEquals(document.inlineBlocks.size, parts.size)
    }

    @Test fun pointerSelectionCanSpanReadingParagraphs() {
        val source = "Первый абзац.\n\nВторой абзац.\n\nТретий абзац."
        val document = runBlocking { ChatMarkdownDocuments.load(source, cache = false) }
        val parts = PaperInlineMessageParts(source, document, emptyList())
        val scene = onPaperUi { ImageComposeScene(500, 300) {
            PaperTheme { Column(Modifier.fillMaxSize().padding(16.dp)) { parts.Content(0) } }
        } }
        try {
            var frame = 0L
            fun render() = onPaperUi { repeat(5) { scene.render(++frame * 32_000_000L).close() } }
            fun nodes(): List<SemanticsNode> {
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            }
            render()
            fun text(label: String) = onPaperUi { nodes().first { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
            } }
            val first = text("Первый абзац.").boundsInRoot
            val last = text("Третий абзац.").boundsInRoot
            val idle = onPaperUi { scene.render(++frame * 32_000_000L).use { it.toComposeImageBitmap().toPixelMap() } }
            val start = Offset(first.left + 3f, first.center.y)
            val end = Offset(last.right - 3f, last.center.y)
            onPaperUi {
                scene.sendPointerEvent(PointerEventType.Press, start, type = PointerType.Mouse,
                    buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Move, end, type = PointerType.Mouse,
                    buttons = PointerButtons(isPrimaryPressed = true))
                scene.sendPointerEvent(PointerEventType.Release, end, type = PointerType.Mouse,
                    buttons = PointerButtons(), button = PointerButton.Primary)
            }
            render()
            val selected = onPaperUi { scene.render(++frame * 32_000_000L).use { it.toComposeImageBitmap().toPixelMap() } }
            fun changed(rect: Rect): Int {
                var count = 0
                for (y in rect.top.toInt() until rect.bottom.toInt())
                    for (x in rect.left.toInt() until rect.right.toInt())
                        if (idle[x, y] != selected[x, y]) count++
                return count
            }
            assertTrue(changed(first) > 10, "The first paragraph must show selection")
            assertTrue(changed(last) > 10, "The last paragraph must show selection")
        } finally { onPaperUi { scene.close() } }
    }

    @Test fun pointerSelectionSpansLazyFragmentsAndSelectAllStillTargetsOneMessage() {
        val source = (1..100).joinToString("\n\n") { "Абзац $it." } + "\n\n" + "конец ".repeat(1200)
        val document = runBlocking { ChatMarkdownDocuments.load(source, cache = false) }
        val parts = PaperInlineMessageParts(source, document, emptyList())
        assertTrue(parts.size > 16)
        val scene = onPaperUi { ImageComposeScene(500, 400) {
            PaperTheme { PaperMessageSelectionContainer {
                PaperLazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxSize()) {
                    items(parts.size) { index -> parts.Content(index) }
                }
            } }
        } }
        try {
            var frame = 0L
            fun render() = onPaperUi { repeat(5) { scene.render(++frame * 32_000_000L).close() } }
            fun nodes(): List<SemanticsNode> {
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            }
            fun text(label: String) = onPaperUi { nodes().first { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
            } }
            render()
            val first = text("Абзац 1.").boundsInRoot
            val second = text("Абзац 2.").boundsInRoot
            val idle = onPaperUi { scene.render(++frame * 32_000_000L).use { it.toComposeImageBitmap().toPixelMap() } }
            val start = Offset(first.left + 2f, first.center.y)
            val end = Offset(second.right - 2f, second.center.y)
            onPaperUi {
                scene.sendPointerEvent(PointerEventType.Press, start, type = PointerType.Mouse,
                    buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Move, end, type = PointerType.Mouse,
                    buttons = PointerButtons(isPrimaryPressed = true))
                scene.sendPointerEvent(PointerEventType.Release, end, type = PointerType.Mouse,
                    buttons = PointerButtons(), button = PointerButton.Primary)
            }
            render()
            val selected = onPaperUi { scene.render(++frame * 32_000_000L).use { it.toComposeImageBitmap().toPixelMap() } }
            fun changed(rect: Rect): Int {
                var count = 0
                for (y in rect.top.toInt() until rect.bottom.toInt())
                    for (x in rect.left.toInt() until rect.right.toInt())
                        if (idle[x, y] != selected[x, y]) count++
                return count
            }
            assertTrue(changed(first) > 10, "Selection starts in the first lazy item")
            assertTrue(changed(second) > 10, "Selection reaches the next lazy item")

            onPaperUi { assertTrue(scene.sendKeyEvent(KeyEvent(Key.A, KeyEventType.KeyDown, isCtrlPressed = true))) }
            render()
            val field = onPaperUi { nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text == source } }
            assertNotNull(field)
            assertEquals(source.length, field.config[SemanticsProperties.TextSelectionRange].end)
        } finally { onPaperUi { scene.close() } }
    }

    @Test fun selectAllInAnInputKeepsTheInputAsItsTarget() {
        val source = "Текст сообщения."
        val document = runBlocking { ChatMarkdownDocuments.load(source, cache = false) }
        val parts = PaperInlineMessageParts(source, document, emptyList())
        var input by mutableStateOf(TextFieldValue("Текст поля"))
        val scene = onPaperUi { ImageComposeScene(500, 300) {
            PaperTheme { PaperMessageSelectionContainer {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    parts.Content(0)
                    BasicTextField(input, onValueChange = { input = it })
                }
            } }
        } }
        try {
            var frame = 0L
            fun render() = onPaperUi { repeat(5) { scene.render(++frame * 32_000_000L).close() } }
            fun nodes(): List<SemanticsNode> {
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            }
            render()
            val message = onPaperUi { nodes().first { it.config.getOrNull(SemanticsProperties.Text)
                ?.any { item -> item.text == source } == true }.boundsInRoot.center }
            val field = onPaperUi { nodes().first { it.config.getOrNull(SemanticsProperties.EditableText)
                ?.text == "Текст поля" }.boundsInRoot.center }
            onPaperUi {
                scene.sendPointerEvent(PointerEventType.Press, message, type = PointerType.Mouse,
                    buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, message, type = PointerType.Mouse,
                    buttons = PointerButtons(), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Press, field, type = PointerType.Mouse,
                    buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, field, type = PointerType.Mouse,
                    buttons = PointerButtons(), button = PointerButton.Primary)
                assertTrue(scene.sendKeyEvent(KeyEvent(Key.A, KeyEventType.KeyDown, isCtrlPressed = true)))
            }
            render()
            assertTrue(onPaperUi { nodes().none { it.config.getOrNull(SemanticsProperties.EditableText)?.text == source } })
            onPaperUi { assertTrue(scene.sendKeyEvent(KeyEvent(Key.A, KeyEventType.KeyDown, isMetaPressed = true))) }
            render()
            assertEquals(0, input.selection.start)
            assertEquals(input.text.length, input.selection.end)
            assertTrue(onPaperUi { nodes().none { it.config.getOrNull(SemanticsProperties.EditableText)?.text == source } })
        } finally { onPaperUi { scene.close() } }
    }

    @Test fun selectAllShortcutSelectsTheWholeMarkdownMessage() {
        val source = "Первый **абзац**.\n\nВторой абзац."
        val document = runBlocking { parsePaperMarkdown(source) }
        val scene = onPaperUi { ImageComposeScene(500, 300) {
            PaperTheme { Column(Modifier.fillMaxSize().padding(16.dp)) {
                PaperMarkdownBody(document, document.node.children)
            } }
        } }
        try {
            var frame = 0L
            fun render() = onPaperUi { repeat(5) { scene.render(++frame * 32_000_000L).close() } }
            fun nodes(): List<SemanticsNode> {
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            }
            render()
            onPaperUi {
                val paragraph = nodes().first { node -> node.config.getOrNull(SemanticsProperties.Text)
                    ?.any { it.text == "Первый абзац." } == true }.boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, paragraph, type = PointerType.Mouse,
                    buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, paragraph, type = PointerType.Mouse,
                    buttons = PointerButtons(), button = PointerButton.Primary)
                assertTrue(scene.sendKeyEvent(KeyEvent(Key.A, KeyEventType.KeyDown, isCtrlPressed = true)))
            }
            render()
            val selected = onPaperUi { nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text == source } }
            assertNotNull(selected)
            val range = selected.config[SemanticsProperties.TextSelectionRange]
            assertEquals(0, range.start)
            assertEquals(source.length, range.end)
            onPaperUi { scene.render(++frame * 32_000_000L).use { image ->
                image.encodeToData()!!.use { data ->
                    File("build/reports/markdown-selection/selected.png").apply { parentFile.mkdirs() }
                        .writeBytes(data.bytes)
                }
            } }

            onPaperUi { scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown)) }
            render()
            assertTrue(onPaperUi { nodes().none { it.config.getOrNull(SemanticsProperties.EditableText)?.text == source } })
            onPaperUi {
                val paragraph = nodes().first { node -> node.config.getOrNull(SemanticsProperties.Text)
                    ?.any { it.text == "Первый абзац." } == true }.boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, paragraph, type = PointerType.Mouse,
                    buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, paragraph, type = PointerType.Mouse,
                    buttons = PointerButtons(), button = PointerButton.Primary)
                assertTrue(scene.sendKeyEvent(KeyEvent(Key.A, KeyEventType.KeyDown, isMetaPressed = true)))
            }
            render()
            assertTrue(onPaperUi { nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == source } })
        } finally { onPaperUi { scene.close() } }
    }

    @Test fun selectAllInAClippedPreviewExposesTheCompleteMessage() {
        val source = (1..90).joinToString("\n\n") { "Абзац $it: " + "содержимое ".repeat(8) }
        val scene = onPaperUi { ImageComposeScene(500, 300) {
            PaperTheme { Column(Modifier.fillMaxSize().padding(16.dp)) { PaperChatMarkdown(source) } }
        } }
        try {
            var frame = 0L
            fun render() = onPaperUi { repeat(5) { scene.render(++frame * 32_000_000L).close() } }
            fun nodes(): List<SemanticsNode> {
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            }
            render()
            var first = onPaperUi { nodes().firstOrNull { node -> node.config.getOrNull(SemanticsProperties.Text)
                ?.any { it.text.startsWith("Абзац 1:") } == true } }
            repeat(30) {
                if (first != null) return@repeat
                render()
                first = onPaperUi { nodes().firstOrNull { node -> node.config.getOrNull(SemanticsProperties.Text)
                    ?.any { it.text.startsWith("Абзац 1:") } == true } }
            }
            val point = assertNotNull(first).boundsInRoot.center
            onPaperUi {
                scene.sendPointerEvent(PointerEventType.Press, point, type = PointerType.Mouse,
                    buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, point, type = PointerType.Mouse,
                    buttons = PointerButtons(), button = PointerButton.Primary)
                assertTrue(scene.sendKeyEvent(KeyEvent(Key.A, KeyEventType.KeyDown, isCtrlPressed = true)))
            }
            render()
            assertTrue(onPaperUi { nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == source } })
            assertTrue(onPaperUi { nodes().none { it.config.getOrNull(SemanticsProperties.Text)
                ?.any { text -> text.text == "Читать далее" } == true } })
        } finally { onPaperUi { scene.close() } }
    }
}
