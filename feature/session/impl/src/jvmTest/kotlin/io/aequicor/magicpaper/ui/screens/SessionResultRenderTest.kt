package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.designsystem.LocalPaperColors
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.awt.EventQueue
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class SessionResultRenderTest {
    @Test fun backgroundWindowDoesNotReadTheResult() = Chat(initialFocused = false).use { chat ->
        assertTrue(chat.read.isEmpty())
        assertTrue(chat.value.value.unread)
        onUi { chat.focused.value = true }
        chat.render()
        assertEquals(setOf("a19"), chat.read.toSet())
        assertFalse(chat.value.value.unread)
    }

    @Test fun newResultStaysUnreadUntilTheEndIsDisplayed() = Chat().use { chat ->
        assertEquals(setOf("a19"), chat.read.toSet())
        chat.read.clear()
        onUi { chat.list.dispatchRawDelta(-1500f) }
        chat.render()
        assertTrue(chat.list.canScrollForward)
        onUi { chat.value.value = chat.value.value.copy(unread = true, messages = chat.value.value.messages +
            CodingMessage("next", CodingRole.AGENT, "Новый результат", createdAt = 30)) }
        chat.render()
        assertTrue(chat.read.isEmpty())
        onUi { chat.list.dispatchRawDelta(100000f) }
        chat.render()
        assertEquals(setOf("next"), chat.read.toSet())
    }

    @Test fun checkboxAcceptsTheDisplayedResultAndCanBeCleared() = Chat(340).use { chat ->
        chat.snapshot("pending-narrow")
        chat.toggle()
        assertTrue(chat.value.value.manuallyVerified)
        assertEquals(CodingSessionStatus.IDLE, chat.value.value.status)
        chat.snapshot("verified-narrow")
        chat.toggle()
        assertFalse(chat.value.value.manuallyVerified)
        assertEquals(CodingSessionStatus.NEEDS_TESTING, chat.value.value.status)
    }

    private class Chat(width: Int = 680, initialFocused: Boolean = true) : AutoCloseable {
        val focused = mutableStateOf(initialFocused)
        val list = LazyListState(Int.MAX_VALUE)
        val read = mutableListOf<String>()
        val value = mutableStateOf(CodingSessionUi(CodingSession("s", "p", "Проверка результата", 1),
            unread = true,
            messages = (0..19).map { CodingMessage("a$it", CodingRole.AGENT,
                "Результат $it. Проверьте работу приложения после изменений.", createdAt = it.toLong()) }))
        private var frame = 0L
        private val scene = onUi { ImageComposeScene(width, 600) {
            MagicPaperTheme {
                Box(Modifier.fillMaxSize().background(LocalPaperColors.current.canvas)) {
                CodingChat(CodingProject("p", "Проект", "/fixture", 1), value.value, false, true,
                    { _, _ -> }, {}, { _, _ -> }, listState = list, windowFocused = focused.value,
                    onResultRead = { read += it; value.value = value.value.copy(unread = false) }, onManualVerification = { id, checked ->
                        value.value = value.value.copy(session = value.value.session.copy(
                            manuallyVerifiedResponseId = if (checked) id else null))
                    })
                }
            }
        } }
        init { render() }
        fun render() { repeat(16) { onUi { scene.render(++frame * 32_000_000L).close() }; Thread.sleep(5) } }
        fun toggle() {
            onUi {
                fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                val checkbox = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                    .single { it.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox &&
                        it.config.getOrNull(SemanticsActions.OnClick) != null }
                assertTrue(checkbox.config[SemanticsActions.OnClick].action!!.invoke())
            }
            render()
        }
        fun snapshot(name: String) = onUi {
            val directory = File("build/reports/session-result").apply { mkdirs() }
            val rendered = scene.render(++frame * 32_000_000L)
            try {
                val data = rendered.encodeToData()!!
                try { File(directory, "$name.png").writeBytes(data.bytes) } finally { data.close() }
            } finally { rendered.close() }
        }
        override fun close() = onUi { scene.close() }
    }

    private companion object {
        fun <T> onUi(block: () -> T): T {
            if (EventQueue.isDispatchThread()) return block()
            var result: Result<T>? = null
            EventQueue.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
    }
}
