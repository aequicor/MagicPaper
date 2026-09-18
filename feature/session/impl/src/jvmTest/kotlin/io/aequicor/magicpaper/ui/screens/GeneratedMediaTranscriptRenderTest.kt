package io.aequicor.magicpaper.ui.screens

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.use
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import java.awt.EventQueue
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class GeneratedMediaTranscriptRenderTest {
    @Test fun savedResearchReadsTextMediaTextWithoutExpandingToolDetails() {
        val media = GeneratedMedia("image", MediaKind.IMAGE, MediaPhase.UNKNOWN, width = 1024, height = 576)
        val message = ChatMessage("answer", ChatRole.AGENT, "Первый абзац\n\nПоследний абзац", 1,
            content = listOf(TranscriptBlock.Markdown("before", "Первый абзац"), TranscriptBlock.Media("image", media),
                TranscriptBlock.Markdown("after", "Последний абзац")))
        val session = ChatSession("chat", "Чат", 1, 1, messages = listOf(message))
        val scene = onUi { ImageComposeScene(420, 700) { PaperTheme { PaperSurface { MessagesList(session, false) } } } }
        try {
            repeat(25) { frame -> onUi { scene.render(frame * 32_000_000L).close() }; Thread.sleep(5) }
            onUi {
                val before = scene.text("Первый абзац").boundsInRoot
                val illustration = scene.nodes().single { it.config.getOrNull(SemanticsProperties.StateDescription) == "Нужно проверить результат" }.boundsInRoot
                val after = scene.text("Последний абзац").boundsInRoot
                assertTrue(before.bottom <= illustration.top)
                assertTrue(illustration.bottom <= after.top)
                val directory = File("build/reports/generated-media-transcript").apply { mkdirs() }
                File(directory, "research-ordered.png").writeBytes(scene.render(900_000_000).use { it.encodeToData()!!.use { data -> data.bytes } })
            }
        } finally { onUi { scene.close() } }
    }

    @Test fun codingGenerationIsVisibleAsAnIllustrationWhileTheToolRuns() {
        val step = CodingStep(CodingStepKind.TOOL, "Служебный вызов", tool = "video.generate", callId = "call", running = true,
            media = GeneratedMedia("video", MediaKind.VIDEO, MediaPhase.GENERATING, width = 1280, height = 720))
        val scene = onUi { ImageComposeScene(420, 360) { PaperTheme { CodingStepRow(step, live = true) } } }
        try {
            onUi { scene.render(0).close() }
            onUi { assertEquals(1, scene.nodes().count { it.config.getOrNull(SemanticsProperties.StateDescription) == "Создаю видео…" }) }
        } finally { onUi { scene.close() } }
    }

    private fun ImageComposeScene.text(text: String) = nodes().first { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
        return semanticsOwners.flatMap { visit(it.unmergedRootSemanticsNode) }
    }
    private fun <T> onUi(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
