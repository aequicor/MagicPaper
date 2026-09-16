package io.aequicor.magicpaper.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.ChatMessage
import io.aequicor.magicpaper.domain.ChatRole
import java.awt.EventQueue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class ChatTranscriptDesignTest {
    private fun <T> onUi(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }

    private fun ImageComposeScene.text(label: String) = nodes().first {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }

    private fun ImageComposeScene.action(label: String) = nodes().first {
        it.config.contains(SemanticsActions.OnClick) &&
            it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { value -> value == label }
    }

    private fun ImageComposeScene.capture(name: String, time: Long) = onUi {
        val directory = File("build/reports/chat-transcript").apply { mkdirs() }
        File(directory, "$name.png").writeBytes(render(time).use { image ->
            image.encodeToData()!!.use { it.bytes }
        })
    }

    @Test fun transcriptGalleryKeepsMessagesAndComposerActionsVisible() {
        data class Case(val name: String, val width: Int, val height: Int = 700, val scale: Float = 1f)
        for (case in listOf(Case("wide", 1000), Case("narrow", 390),
            Case("large-text", 720, 900, 2f), Case("empty", 390), Case("busy", 390))) {
            val scene = onUi { ImageComposeScene(case.width, case.height) {
                CompositionLocalProvider(LocalDensity provides Density(1f, case.scale)) {
                    when (case.name) {
                        "empty" -> EmptyChatTranscriptPreview()
                        "busy" -> BusyChatTranscriptPreview()
                        else -> ChatTranscriptPreview()
                    }
                }
            } }
            try {
                repeat(20) { onUi { scene.render(it * 32_000_000L).close() }; Thread.sleep(5) }
                onUi {
                    val input = scene.nodes().first { it.config.contains(SemanticsActions.SetText) }
                    val attach = scene.nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Инструменты и параметры сессии") }
                    val plus = scene.text("+")
                    assertTrue(plus.boundsInRoot.top >= attach.boundsInRoot.top && plus.boundsInRoot.bottom <= attach.boundsInRoot.bottom,
                        "Attachment glyph must fit the control at ${case.scale} text scale")
                    val labels = if (case.name == "busy") listOf("Пауза") else listOf("Отправить")
                    for (label in labels) {
                        val action = scene.action(label)
                        assertTrue(action.boundsInRoot.left >= 0 && action.boundsInRoot.right <= case.width, "$label clipped: ${case.name}")
                        assertTrue(action.boundsInRoot.bottom <= case.height, "$label below viewport: ${case.name}")
                    }
                    if (case.name == "empty") {
                        assertTrue(scene.text("Что будем исследовать?").boundsInRoot.bottom < input.boundsInRoot.top)
                        assertTrue(input.boundsInRoot.center.y < case.height * .75f, "New question input belongs near the center")
                    } else {
                        val transcript = scene.nodes().first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                        assertTrue(transcript.boundsInRoot.bottom > input.boundsInRoot.bottom, "Messages scroll behind composer")
                        val last = scene.text(if (case.name == "busy") "Исследую вопрос…" else "Сначала разберём задачи на понедельник.")
                        assertTrue(last.boundsInRoot.bottom <= input.boundsInRoot.top - 12, "Last content is covered by composer: ${case.name}")
                    }
                }
                scene.capture(case.name, 800_000_000L)
            } finally { onUi { scene.close() } }
        }
    }

    @Test fun busyTranscriptUsesOnlyPlatformSaveableLazyListKeys() {
        fun platformSaveable(value: Any?): Boolean = when (value) {
            null, is String, is Boolean, is Number, is Char -> true
            is MutableState<*> -> platformSaveable(value.value)
            is List<*> -> value.all(::platformSaveable)
            is Array<*> -> value.all(::platformSaveable)
            is Map<*, *> -> value.all { (key, item) -> platformSaveable(key) && platformSaveable(item) }
            else -> false
        }
        val registry = SaveableStateRegistry(restoredValues = null, canBeSaved = ::platformSaveable)
        val scene = onUi { ImageComposeScene(390, 700) {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                BusyChatTranscriptPreview()
            }
        } }
        try {
            repeat(3) { onUi { scene.render(it * 32_000_000L).close() } }
            onUi { registry.performSave() }
        } finally { onUi { scene.close() } }
    }

    @Test fun growingComposerKeepsTailReadableAndScrollButtonAboveInput() {
        val session = chatTranscriptPreviewSession().copy(messages = (0..30).map {
            ChatMessage("message-$it", ChatRole.USER, "Сообщение $it", it.toLong())
        })
        val scene = onUi { ImageComposeScene(640, 700) { ChatTranscriptPreview(session) } }
        var frame = 0L
        fun render() { repeat(20) { onUi { scene.render(++frame * 32_000_000L).close() }; Thread.sleep(5) } }
        try {
            render()
            val initialTop = onUi { scene.nodes().first { it.config.contains(SemanticsActions.SetText) }.boundsInRoot.top }
            onUi {
                scene.nodes().first { it.config.contains(SemanticsActions.SetText) }
                    .config[SemanticsActions.SetText].action!!.invoke(AnnotatedString((1..6).joinToString("\n") { "Строка черновика $it" }))
            }
            render()
            onUi {
                val input = scene.nodes().first { it.config.contains(SemanticsActions.SetText) }
                assertTrue(input.boundsInRoot.top < initialTop, "Exercise composer growth")
                assertTrue(scene.text("Сообщение 30").boundsInRoot.bottom < input.boundsInRoot.top - 12, "Growing composer must not cover tail")
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(100f, 180f), scrollDelta = Offset(0f, -15f))
            }
            render()
            onUi {
                val arrow = scene.nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("К концу чата") }
                val input = scene.nodes().first { it.config.contains(SemanticsActions.SetText) }
                assertTrue(arrow.boundsInRoot.bottom <= input.boundsInRoot.top, "Scroll action stays above composer")
                arrow.config[SemanticsActions.OnClick].action!!.invoke()
            }
            render()
            onUi {
                val input = scene.nodes().first { it.config.contains(SemanticsActions.SetText) }
                assertTrue(scene.text("Сообщение 30").boundsInRoot.bottom < input.boundsInRoot.top - 12)
            }
            scene.capture("multiline", ++frame * 32_000_000L)
        } finally { onUi { scene.close() } }
    }
}
