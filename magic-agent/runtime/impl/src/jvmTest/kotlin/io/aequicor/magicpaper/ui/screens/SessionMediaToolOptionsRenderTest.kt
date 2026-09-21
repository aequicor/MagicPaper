package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
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
class SessionMediaToolOptionsRenderTest {
    @Test fun bothComposerMenusIndependentlyDisableMediaWithoutSendingOrReplacingTheDraft() {
        for (coding in listOf(false, true)) for ((width, scale) in listOf(390 to 1f, 720 to 2f)) {
            val draft = CodingComposerDraft().apply { text.value = "Сохранённый вопрос" }
            var permissions by mutableStateOf(SessionMediaTools())
            var sends = 0
            var settings = 0
            val scene = onUi { ImageComposeScene(width, 820) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale), LocalChatPresentation provides DefaultChatPresentation) {
                    PaperTheme { PaperSurface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        val options: @Composable () -> Unit = {
                            SessionMediaToolOptions(permissions, mapOf(
                                MediaKind.IMAGE to MediaConnectionStatus(MediaKind.IMAGE, MediaAvailability.AVAILABLE),
                                MediaKind.VIDEO to MediaConnectionStatus(MediaKind.VIDEO, MediaAvailability.UNAVAILABLE)),
                                { kind, enabled -> permissions = permissions.withEnabled(kind, enabled) }, { settings++ })
                        }
                        if (coding) CodingComposer(state = draft, enabled = true, busy = false, mediaOptions = options,
                            onSend = { _, _ -> sends++ }, onAbort = {}, onPickAttachments = { _, _ -> })
                        else ResearchComposer(state = draft, enabled = true, busy = false, paused = false,
                            profile = null, contextUsage = null, contextCompacting = false,
                            placeholder = "Ваш вопрос…", onOpenSwitcher = {},
                            onSend = { _, _ -> sends++ }, onPause = {},
                            onResume = { _, _ -> sends++ }, onClarify = { _, _ -> sends++ },
                            onPickAttachments = { _, _ -> }, onPasteAttachments = { _, _ -> false }, mediaOptions = options)
                    } } }
                }
            } }
            var time = 0L
            fun settle() = repeat(20) { onUi { scene.render(time.also { time += 16_000_000 }).close() } }
            fun click(label: String) = onUi { scene.action(label).config[SemanticsActions.OnClick].action!!.invoke() }
            try {
                settle()
                val editor = onUi { scene.nodes().single { it.config.contains(SemanticsProperties.EditableText) }.id }
                click("Показать параметры"); settle()
                onUi {
                    val image = scene.action("Создание изображений")
                    val video = scene.action("Создание видео")
                    assertEquals(ToggleableState.On, image.config[SemanticsProperties.ToggleableState])
                    assertEquals(ToggleableState.On, video.config[SemanticsProperties.ToggleableState])
                    assertFalse(video.config.contains(SemanticsProperties.Disabled), "Unavailable connections retain an independent session permission")
                    assertTrue(image.boundsInRoot.top >= 0 && image.boundsInRoot.right <= width)
                    assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Подключение недоступно" } })
                    File("build/reports/generated-media-transcript/${if (coding) "coding" else "research"}-options-$width-$scale.png")
                        .apply { parentFile.mkdirs() }.writeBytes(scene.render(time).use { image -> image.encodeToData()!!.use { it.bytes } })
                }
                click("Создание изображений"); settle()
                onUi { assertFalse(permissions.images); assertTrue(permissions.videos) }
                click("Создание видео"); settle()
                onUi {
                    assertFalse(permissions.images); assertFalse(permissions.videos)
                    assertEquals(ToggleableState.Off, scene.action("Создание видео").config[SemanticsProperties.ToggleableState])
                    assertEquals(editor, scene.nodes().single { it.config.contains(SemanticsProperties.EditableText) }.id)
                    assertEquals("Сохранённый вопрос", draft.text.value)
                    assertEquals(0, sends)
                }
                click("Настроить модели"); settle()
                onUi { assertEquals(1, settings) }
            } finally { onUi { scene.close() } }
        }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
        return semanticsOwners.flatMap { visit(it.rootSemanticsNode) }
    }
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
