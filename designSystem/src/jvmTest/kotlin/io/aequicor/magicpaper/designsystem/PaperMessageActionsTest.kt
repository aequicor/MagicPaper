package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class PaperMessageActionsTest {
    @Test fun hoverFocusAndSecondaryClickExposeActionsWithoutMovingText() {
        var forks = 0
        ImageComposeScene(430, 300) {
            PaperTheme {
                PaperSurface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp)) {
                        PaperMessageActions({}, onFork = { forks++ }, onDelete = {}, historyEnabled = false) {
                            SelectionContainer { PaperText("Ответ с выделяемым текстом") }
                        }
                    }
                }
            }
        }.use { scene ->
            var frame = 0L
            fun render(name: String): ByteArray {
                repeat(5) { scene.render(++frame * 32_000_000L).close() }
                return scene.render(++frame * 32_000_000L).use { image ->
                    image.encodeToData()!!.use { data -> data.bytes.also {
                        val file = File("build/reports/message-actions/$name.png")
                        file.parentFile.mkdirs()
                        file.writeBytes(it)
                    } }
                }
            }
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            fun nodes() = scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
            fun text(label: String) = nodes().first {
                it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == label } == true
            }
            val idle = render("idle")
            val bounds = text("Ответ с выделяемым текстом").boundsInRoot
            assertTrue(nodes().none { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == "Копировать целиком" } == true })
            scene.sendPointerEvent(PointerEventType.Move, bounds.center, type = PointerType.Mouse)
            val hover = render("hover")
            assertFalse(idle.contentEquals(hover), "Hover reveals the menu opener")
            assertEquals(bounds, text("Ответ с выделяемым текстом").boundsInRoot)
            scene.sendPointerEvent(PointerEventType.Move, Offset(420f, 280f), type = PointerType.Mouse)
            render("exit")
            val opener = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Действия с сообщением") == true }
            assertTrue(opener.config[SemanticsActions.RequestFocus].action!!.invoke())
            val focused = render("focus")
            assertFalse(idle.contentEquals(focused))
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyUp))
            render("keyboard-menu")
            assertTrue(text("Удалить из истории и контекста").config.contains(SemanticsProperties.Disabled))
            text("Форк до этого сообщения").config[SemanticsActions.OnClick].action!!.invoke()
            render("closed")
            assertEquals(1, forks)
            scene.sendPointerEvent(PointerEventType.Press, bounds.center, type = PointerType.Mouse,
                buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
            scene.sendPointerEvent(PointerEventType.Release, bounds.center, type = PointerType.Mouse,
                buttons = PointerButtons(), button = PointerButton.Secondary)
            render("context-menu")
            text("Форк до этого сообщения").config[SemanticsActions.OnClick].action!!.invoke()
            render("context-closed")
            assertEquals(2, forks, "Secondary click on selectable message text opens the same actions")
        }
    }
}
