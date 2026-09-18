package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.aequicor.magicpaper.designsystem.PaperResearchReading
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.domain.Attachment
import io.aequicor.magicpaper.domain.ChatMessage
import io.aequicor.magicpaper.domain.ChatRole
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.CodingRunCheckpoint
import io.aequicor.magicpaper.ui.components.DefaultChatPresentation
import io.aequicor.magicpaper.ui.components.LocalChatPresentation
import java.awt.EventQueue
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * A research run interrupted by a crash restores a message that carries no answer text yet, and a
 * question can arrive with only an attachment. Such a block parses into zero fragments, so the
 * transcript keeps one blank fragment for it: opening the chat must render the surrounding message
 * (status, attachments) instead of throwing out of the pane's recomposition and killing the window.
 */
@OptIn(ExperimentalComposeUiApi::class)
class RestoredEmptyResearchAnswerTest {
    private class Transcript(session: ChatSession, busy: Boolean) : AutoCloseable {
        private var frame = 0L
        private val scene = onUi {
            ImageComposeScene(760, 620) {
                PaperTheme {
                    PaperResearchReading {
                        CompositionLocalProvider(LocalChatPresentation provides DefaultChatPresentation) {
                            MessagesList(session, busy, listState = LazyListState())
                        }
                    }
                }
            }
        }

        init {
            // Both parsers are asynchronous; this also lets the crash surface from a later frame.
            repeat(24) { onUi { scene.render(++frame * 32_000_000L).close() }; Thread.sleep(5) }
        }

        fun texts(): List<String> = onUi {
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
        }

        override fun close() = onUi { scene.close() }
    }

    @Test fun savedAnswerWithoutTextKeepsTheTranscriptOpenable() {
        val session = ChatSession("restored", "Исследование", 0, 1, messages = listOf(
            ChatMessage("question", ChatRole.USER, "Исследуй тему", 0),
            ChatMessage("answer", ChatRole.AGENT, "", 1)))
        Transcript(session, busy = false).use { screen ->
            val texts = screen.texts()
            assertTrue(texts.any { it.contains("Исследуй тему") }, "The question stays readable: $texts")
            assertFalse(texts.any { it == "Подготавливаю сообщение…" },
                "An empty saved answer is already parsed, so it must not read as still preparing")
        }
    }

    @Test fun pendingRunRestoredBeforeTheFirstChunkKeepsItsOwnStatus() {
        val session = ChatSession("paused", "Исследование", 0, 1,
            messages = listOf(ChatMessage("question", ChatRole.USER, "Исследуй тему", 0)),
            pendingRun = CodingRunCheckpoint("question", "Исследуй тему", responseId = "live"))
        Transcript(session, busy = false).use { screen ->
            val texts = screen.texts()
            assertTrue(texts.any { it == "Исследование приостановлено" },
                "The interrupted run stays recoverable: $texts")
        }
    }

    @Test fun questionWithOnlyAnAttachmentKeepsThatAttachmentReadable() {
        val session = ChatSession("attachment", "Исследование", 0, 1, messages = listOf(
            ChatMessage("question", ChatRole.USER, "", 0,
                attachments = listOf(Attachment.fromBytes("note.txt", "text/plain", "контекст".encodeToByteArray()))),
            ChatMessage("answer", ChatRole.AGENT, "Ответ по вложению", 1)))
        Transcript(session, busy = false).use { screen ->
            val texts = screen.texts()
            assertTrue(texts.any { it.contains("note.txt") },
                "A blank question still owns its attachment after its block parses: $texts")
            assertTrue(texts.any { it.contains("Ответ по вложению") })
        }
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
