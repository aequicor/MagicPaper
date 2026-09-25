package io.aequicor.magicpaper.ui.screens

import androidx.compose.material3.Surface
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.aequicor.magicpaper.domain.CodingMessage
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingRole
import io.aequicor.magicpaper.domain.CodingSession
import io.aequicor.magicpaper.domain.CodingStep
import io.aequicor.magicpaper.domain.CodingStepKind
import io.aequicor.magicpaper.domain.fullCopyText
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.awt.EventQueue
import java.awt.datatransfer.DataFlavor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class CodingResponseSelectionTest {
    private fun <T> onUi(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    @Test fun selectAllFromToolOrAnswerIncludesTheWholeAgentResponse() {
        val response = CodingMessage("agent-response", CodingRole.AGENT, "", steps = listOf(
            CodingStep(CodingStepKind.TOOL, "⚒ read_file · Widget.kt", result = "TOOL_OUTPUT_42", id = "tool"),
            CodingStep(CodingStepKind.ANSWER, "Первый **итог** агента.", id = "answer"),
        ), createdAt = 2)
        val later = CodingMessage("later-response", CodingRole.AGENT, "", steps = listOf(
            CodingStep(CodingStepKind.ANSWER, "Соседний ответ агента.", id = "later"),
        ), createdAt = 3)
        val expected = response.fullCopyText()
        val list = LazyListState()
        var copied: ClipEntry? = null
        val clipboard = object : Clipboard {
            override suspend fun getClipEntry(): ClipEntry? = copied
            override suspend fun setClipEntry(clipEntry: ClipEntry?) { copied = clipEntry }
            override val nativeClipboard: Any = java.awt.datatransfer.Clipboard("selection-test")
        }
        val scene = onUi { ImageComposeScene(850, 720) {
            CompositionLocalProvider(LocalClipboard provides clipboard) { MagicPaperTheme { Surface {
                CodingChat(CodingProject("project", "Проект", "/project", 1),
                    CodingSessionUi(CodingSession("session", "project", "Сессия", 1),
                        messages = listOf(CodingMessage("user", CodingRole.USER, "Проверь файл", createdAt = 1), response, later)),
                    busy = false, engineReady = true, onSend = { _, _ -> }, onAbort = {},
                    onPickAttachments = { _, _ -> }, listState = list)
            } } }
        } }
        try {
            var frame = 0L
            fun render() = onUi { repeat(12) { scene.render(++frame * 32_000_000L).close() } }
            fun nodes(): List<SemanticsNode> = onUi {
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            }
            fun point(label: String): Offset = assertNotNull(nodes().firstOrNull { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { label in it.text } == true
            }, "Text '$label' must be visible").boundsInRoot.center
            fun renderedBounds(label: String): Rect = assertNotNull(nodes().firstOrNull { node ->
                node.config.getOrNull(SemanticsProperties.EditableText) == null &&
                    node.config.getOrNull(SemanticsProperties.Text)?.any { label in it.text } == true
            }, "Rendered '$label' must remain visible").boundsInRoot
            fun click(position: Offset) = onUi {
                scene.sendPointerEvent(PointerEventType.Press, position, type = PointerType.Mouse,
                    buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, position, type = PointerType.Mouse,
                    buttons = PointerButtons(), button = PointerButton.Primary)
            }
            fun selectedResponse() {
                val field = assertNotNull(nodes().firstOrNull {
                    it.config.getOrNull(SemanticsProperties.EditableText) != null &&
                        it.config.getOrNull(SemanticsProperties.TextSelectionRange)?.end == expected.length
                }, "Ctrl+A must select the full agent response")
                val range = field.config[SemanticsProperties.TextSelectionRange]
                assertEquals(0, range.start)
                assertEquals(expected.length, range.end)
                assertEquals(1, field.config[SemanticsProperties.EditableText].text.length,
                    "The offscreen selection field must lay out a constant-size placeholder")
                assertTrue("TOOL_OUTPUT_42" in expected)
                assertTrue("Первый **итог** агента." in expected)
                assertTrue("Соседний ответ" !in expected)
            }
            render()
            click(point("read_file"))
            render()
            val toolBefore = renderedBounds("read_file")
            val answerBefore = renderedBounds("Первый итог агента.")
            val outputBefore = renderedBounds("TOOL_OUTPUT_42")
            onUi { assertTrue(scene.sendKeyEvent(KeyEvent(Key.A, KeyEventType.KeyDown, isCtrlPressed = true))) }
            render()
            selectedResponse()
            assertEquals(toolBefore, renderedBounds("read_file"), "Tool card must keep its position")
            assertEquals(answerBefore, renderedBounds("Первый итог агента."), "Markdown answer must keep its position")
            assertEquals(outputBefore, renderedBounds("TOOL_OUTPUT_42"), "Expanded tool output must keep its layout")
            onUi { assertTrue(scene.sendKeyEvent(KeyEvent(Key.C, KeyEventType.KeyDown, isMetaPressed = true))) }
            render()
            assertEquals(expected, copied?.asAwtTransferable?.getTransferData(DataFlavor.stringFlavor),
                "Copy must include tool call, output and answer")
            onUi { scene.render(++frame * 32_000_000L).use { image ->
                image.encodeToData()!!.use { data ->
                    File("build/reports/coding-response-selection/whole-response.png").apply { parentFile.mkdirs() }
                        .writeBytes(data.bytes)
                }
            } }

            onUi { scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown)) }
            render()
            assertTrue(nodes().none { it.config.getOrNull(SemanticsProperties.EditableText) != null &&
                it.config.getOrNull(SemanticsProperties.TextSelectionRange)?.end == expected.length })
            click(point("read_file"))
            render()
            assertTrue(nodes().none { node -> node.config.getOrNull(SemanticsProperties.Text)
                ?.any { "TOOL_OUTPUT_42" in it.text } == true }, "Tool output must be collapsed")
            val collapsedToolBefore = renderedBounds("read_file")
            click(point("Первый итог агента."))
            render()
            onUi { assertTrue(scene.sendKeyEvent(KeyEvent(Key.A, KeyEventType.KeyDown, isCtrlPressed = true))) }
            render()
            selectedResponse()
            assertEquals(collapsedToolBefore, renderedBounds("read_file"), "Collapsed tool card must keep its position")
            assertTrue(nodes().none { node -> node.config.getOrNull(SemanticsProperties.Text)
                ?.any { "TOOL_OUTPUT_42" in it.text } == true }, "Select all must not expand tool output")
            assertTrue(onUi { list.layoutInfo.visibleItemsInfo.any { it.key == "agent-response:step:answer" } },
                "Selection stays on the answer row where Ctrl+A was invoked")
        } finally { onUi { scene.close() } }
    }
}
