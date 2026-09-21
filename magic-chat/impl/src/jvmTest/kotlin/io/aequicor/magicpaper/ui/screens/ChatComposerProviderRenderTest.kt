package io.aequicor.magicpaper.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.CodingEngine
import java.awt.EventQueue
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class ChatComposerProviderRenderTest {
    @Test fun providerOptionsHaveMediaControlsAndNoNativeEngineAtEverySupportedSize() {
        for ((width, height, scale) in listOf(Triple(720, 440, 1f), Triple(390, 560, 1f), Triple(720, 700, 2f))) {
            val scene = onUi { ImageComposeScene(width, height) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) { ChatComposerPreview() }
            } }
            var time = 0L
            fun settle() = repeat(20) { onUi { scene.render(time.also { time += 16_000_000 }).close() } }
            try {
                settle()
                onUi {
                    scene.nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Показать параметры") }
                        .config[SemanticsActions.OnClick].action!!.invoke()
                }
                settle()
                onUi {
                    val labels = scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                    assertTrue("Создание изображений" in labels)
                    assertTrue("Создание видео" in labels)
                    assertFalse("Движок" in labels)
                    assertFalse(CodingEngine.entries.any { it.title in labels })
                    val attach = scene.nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Прикрепить файлы") }
                    assertTrue(attach.boundsInRoot.left >= 0 && attach.boundsInRoot.right <= width)
                    assertTrue(attach.boundsInRoot.bottom <= height)
                    File("build/reports/chat-provider-composer/options-$width-$scale.png").apply { parentFile.mkdirs() }
                        .writeBytes(scene.render(time).use { it.encodeToData()!!.use { data -> data.bytes } })
                }
            } finally { onUi { scene.close() } }
        }
    }

    @Test fun interruptedChatShowsSavedAnswerCheckWithinNarrowAndLargeTextBounds() {
        for ((width, height, scale) in listOf(Triple(390, 560, 1f), Triple(720, 700, 2f))) {
            val scene = onUi { ImageComposeScene(width, height) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) { ChatRecoveryComposerPreview() }
            } }
            try {
                onUi {
                    repeat(20) { scene.render(it * 16_000_000L).close() }
                    val check = scene.nodes().single {
                        it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Проверить ответ")
                    }
                    assertTrue(check.boundsInRoot.left >= 0 && check.boundsInRoot.right <= width)
                    assertTrue(check.boundsInRoot.top >= 0 && check.boundsInRoot.bottom <= height)
                    assertNotNull(check.config.getOrNull(SemanticsActions.OnClick)?.action)
                    assertTrue(scene.nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Проверить ответ" } })
                    assertFalse(scene.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Продолжить") })
                    File("build/reports/chat-provider-composer/recovery-$width-$scale.png").apply { parentFile.mkdirs() }
                        .writeBytes(scene.render(320_000_000).use { it.encodeToData()!!.use { data -> data.bytes } })
                }
            } finally { onUi { scene.close() } }
        }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        return semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
    }

    private fun <T> onUi(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
