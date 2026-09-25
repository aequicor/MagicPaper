package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.components.CodingComposerDraft
import io.aequicor.magicpaper.ui.components.DefaultChatPresentation
import io.aequicor.magicpaper.ui.components.LocalChatPresentation
import java.awt.EventQueue
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class ComposerOptionsRenderTest {
    @Test fun chatAndSessionOptionsAttachFilesWithoutSendingOrReplacingTheEditor() {
        for (coding in listOf(false, true)) for ((width, scale, enabled) in listOf(
            Triple(720, 1f, true), Triple(390, 1f, true), Triple(720, 2f, true), Triple(390, 2f, true), Triple(390, 1f, false))) {
            val draft = CodingComposerDraft().apply { text.value = "Вопрос с уже введённым текстом" }
            val file = Attachment.fromBytes("Материалы.txt", "text/plain", "Текст для исследования".encodeToByteArray())
            var picks = 0
            var sends = 0
            var engine by mutableStateOf(CodingEngine.PI)
            val scene = onUi { ImageComposeScene(width, 700) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale), LocalChatPresentation provides DefaultChatPresentation) {
                    PaperTheme { PaperSurface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                            val picker: (Int, (List<Attachment>) -> Unit) -> Unit = { count, picked ->
                                assertEquals(draft.attachments.value.size, count)
                                picks++
                                picked(listOf(file))
                            }
                            if (coding) CodingComposer(state = draft, enabled = enabled, busy = false,
                                engine = engine, onEngineChange = { engine = it },
                                onInteractionMode = {}, onSearchProvider = {}, onWorktreeChange = {},
                                onSend = { _, _ -> sends++ }, onAbort = {}, onPickAttachments = picker)
                            else ResearchComposer(state = draft, enabled = enabled, busy = false, paused = false,
                                profile = null, contextUsage = null, contextCompacting = false,
                                placeholder = "Ваш вопрос…", onOpenSwitcher = {},
                                mediaOptions = { SessionMediaToolOptions(SessionMediaTools(), emptyMap(), { _, _ -> }, {}) },
                                onSend = { _, _ -> sends++ }, onPause = {},
                                onResume = { _, _ -> sends++ }, onClarify = { _, _ -> sends++ },
                                onPickAttachments = picker, onPasteAttachments = { _, _ -> false })
                        }
                    } }
                }
            } }
            var time = 0L
            fun settle() = repeat(20) { onUi { scene.render(time.also { time += 16_000_000 }).close() } }
            fun click(label: String) = onUi { scene.action(label).config[SemanticsActions.OnClick].action!!.invoke() }
            try {
                settle()
                val inputId = onUi { scene.editor().id }
                click("Прикрепить файлы"); settle()
                click("Показать параметры"); settle()
                onUi {
                    assertEquals(1, picks)
                    assertEquals(listOf(file), draft.attachments.value)
                    assertEquals(0, sends)
                    val labels = scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                    if (coding) {
                        assertTrue("Движок" in labels, "Native session offers engine selection")
                        assertTrue(engine.title in labels, "Current engine remains visible")
                    }
                    else {
                        assertFalse("Движок" in labels, "Provider chat must not offer a native engine")
                        assertFalse(CodingEngine.entries.any { it.title in labels })
                    }
                    if (!enabled) assertTrue(scene.action("Отправить").config.contains(SemanticsProperties.Disabled),
                        "Preparing attachments must remain possible before the engine is ready")
                    assertEquals(inputId, scene.editor().id)
                    assertEquals("Вопрос с уже введённым текстом", draft.text.value)
                    val input = scene.editor().boundsInRoot
                    val send = scene.action("Отправить").boundsInRoot
                    val attach = scene.action("Прикрепить файлы").boundsInRoot
                    assertTrue(input.top >= 0 && attach.top >= input.bottom, "Direct attachment action stays below the usable editor")
                    assertTrue(attach.right <= width && attach.bottom <= 700)
                    assertTrue(scene.semanticsOwners.any { owner ->
                        val ids = descendants(owner.rootSemanticsNode).map { it.id }
                        inputId in ids && scene.action("Прикрепить файлы").id in ids
                    }, "Attachment action and editor share the same window")
                    File("build/reports/composer-options/${if (coding) "session" else "chat"}-$width-$scale${if (enabled) "" else "-unavailable"}.png")
                        .apply { parentFile.mkdirs() }.writeBytes(scene.render(time).use { image -> image.encodeToData()!!.use { it.bytes } })
                }
                click("Скрыть параметры"); settle()
                onUi {
                    assertTrue(scene.action("Прикрепить файлы").boundsInRoot.height > 0)
                    assertTrue(scene.action("Отправить").boundsInRoot.bottom <= 692, "Bottom gap remains visible")
                    assertEquals(inputId, scene.editor().id)
                    assertEquals(listOf(file), draft.attachments.value)
                }
            } finally { onUi { scene.close() } }
        }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        return semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
    }
    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    private fun ImageComposeScene.editor() = nodes().single { it.config.contains(SemanticsProperties.EditableText) }
    private fun ImageComposeScene.action(label: String) = nodes().single {
        it.config.contains(SemanticsActions.OnClick) && (it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label) ||
            it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label })
    }
    private fun <T> onUi(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
