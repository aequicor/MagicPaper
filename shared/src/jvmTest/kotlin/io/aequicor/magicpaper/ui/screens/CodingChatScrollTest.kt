package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.CodingStep
import io.aequicor.magicpaper.domain.CodingStepKind
import io.aequicor.magicpaper.ui.components.ChatScrollItem
import io.aequicor.magicpaper.ui.components.stickToBottom
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlin.math.abs
import kotlin.test.*

/** Actual tool disclosure, pointer events and lazy-list measurement, including offscreen headers. */
class CodingChatScrollTest {
    private class Chat(trailingHeight: Int = 900) : AutoCloseable {
        val list = LazyListState()
        val tail = mutableStateOf(trailingHeight)
        val session = mutableStateOf("session-1")
        var commandTop = 0f
        var commandBounds = Rect.Zero
        private var frame = 0L
        private val scene = ImageComposeScene(680, 600) {
            MagicPaperTheme {
                val scroll = stickToBottom(list, session.value)
                LazyColumn(state = list, contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(12, key = { "history-$it" }) { Text("Earlier message $it", Modifier.height(150.dp)) }
                    item(key = "message") {
                        ChatScrollItem(scroll, "message") {
                            Column {
                                Spacer(Modifier.height(240.dp))
                                Box(Modifier.fillMaxWidth().onGloballyPositioned {
                                    commandTop = it.positionInRoot().y
                                    commandBounds = it.boundsInRoot()
                                }) {
                                    CodingStepRow(CodingStep(CodingStepKind.EXEC,
                                        (1..36).joinToString("\n") { "command line $it: checking the project" },
                                        callId = "long-command", result = "Command completed"), live = false)
                                }
                                Spacer(Modifier.height(tail.value.dp))
                            }
                        }
                    }
                }
            }
        }

        init { render() }
        fun render() { repeat(16) { scene.render(++frame * 32_000_000L).close(); Thread.sleep(10) } }
        fun position(offset: Int) {
            val item = list.layoutInfo.visibleItemsInfo.single { it.key == "message" }
            list.dispatchRawDelta((item.offset + offset).toFloat())
            render()
        }
        fun click(y: Float = commandTop + 18f) {
            assertTrue(y in 0f..599f, "Disclosure must be visible before clicking: $y")
            scene.sendPointerEvent(PointerEventType.Press, Offset(120f, y))
            scene.sendPointerEvent(PointerEventType.Release, Offset(120f, y))
            render()
        }
        override fun close() = scene.close()
    }

    @Test fun visibleCommandStaysInPlaceWhenExpandedAndCollapsed() = Chat().use { chat ->
        chat.position(140)
        val top = chat.commandTop
        val height = chat.commandBounds.height
        chat.click()
        assertTrue(chat.commandBounds.height > height * 3, "Click must expand the real command")
        assertTrue(abs(chat.commandTop - top) <= 1, "Expansion moved the command")
        chat.click()
        assertTrue(abs(chat.commandBounds.height - height) <= 1, "Click must collapse the command")
        assertTrue(abs(chat.commandTop - top) <= 1, "Collapse moved the command")
    }

    @Test fun collapsingMultilineCommandWithItsTopOffscreenKeepsCollapsedRowVisible() = Chat().use { chat ->
        chat.position(140)
        val height = chat.commandBounds.height
        chat.click()
        chat.position(600)
        assertTrue(chat.commandTop < -300, "Read the middle of the expanded command")
        chat.click(100f)
        assertTrue(chat.commandTop in 0f..32f, "Collapsed command disappeared above viewport: ${chat.commandTop}")
        assertTrue(abs(chat.commandBounds.height - height) <= 1)
        assertEquals(12, chat.list.firstVisibleItemIndex, "Must stay in the same message")
    }

    @Test fun expandingAtBottomDoesNotAutoscrollPastTheCommand() = Chat(trailingHeight = 20).use { chat ->
        assertFalse(chat.list.canScrollForward)
        val top = chat.commandTop
        chat.click()
        assertTrue(abs(chat.commandTop - top) <= 1, "Bottom following overrode disclosure")
        assertTrue(chat.list.canScrollForward, "Expanded output should extend below the viewport")
        chat.tail.value += 200
        chat.render()
        assertTrue(abs(chat.commandTop - top) <= 1, "New output must not interrupt reading the command")
    }

    @Test fun outputStillFollowsAtBottomAndSessionSwitchResetsReadingPosition() = Chat().use { chat ->
        assertFalse(chat.list.canScrollForward)
        chat.tail.value += 200
        chat.render()
        assertFalse(chat.list.canScrollForward, "Follow newly arriving output")
        chat.position(140)
        val top = chat.commandTop
        chat.tail.value += 200
        chat.render()
        assertEquals(top, chat.commandTop, "Reading history pauses following")
        chat.list.dispatchRawDelta(10000f)
        chat.render()
        assertFalse(chat.list.canScrollForward)
        chat.tail.value += 200
        chat.render()
        assertFalse(chat.list.canScrollForward, "Returning to the bottom resumes following")
        chat.position(140)
        chat.session.value = "session-2"
        chat.render()
        assertFalse(chat.list.canScrollForward, "Switching sessions opens the latest output")
    }
}
