package io.aequicor.magicpaper.designsystem

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class PaperComposerOptionsTest {
    @Test fun panelSlidesBelowEditorWithoutLosingDraftAndEscapeRestoresDisclosureFocus() {
        for ((width, scale) in listOf(640 to 1f, 360 to 1f, 480 to 2f)) {
            val scene = onPaperUi { ImageComposeScene(width, 480) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    PaperComposerOptionsPreview(initiallyExpanded = false)
                }
            } }
            var time = 0L
            fun frame() = onPaperUi { scene.render(time.also { time += 16_000_000 }).close() }
            fun settle() = repeat(24) { frame() }
            fun toggle(open: Boolean) = onPaperUi {
                scene.action(if (open) "Показать параметры и ресурсы" else "Скрыть параметры и ресурсы")
                    .config[SemanticsActions.OnClick].action!!.invoke()
            }
            try {
                settle()
                val editorId = onPaperUi {
                    scene.editor().also {
                        it.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Мой вопрос и уточнение"))
                    }.id
                }
                settle()
                onPaperUi { scene.editor().config[SemanticsActions.SetSelection].action!!.invoke(4, 10, false) }
                settle()
                val closedHeight = onPaperUi { scene.tag("composer-frame").boundsInRoot.height }
                onPaperUi { scene.capture("closed-$width-$scale", time) }
                toggle(true)
                val heights = (0..18).map { frame(); onPaperUi { scene.tag("composer-options-panel").boundsInRoot.height } }
                val openHeight = heights.last()
                assertTrue(openHeight > 0)
                assertTrue(heights.any { it > 0 && it < openHeight }, "Reveal must pass through intermediate heights")
                assertTrue(heights.zipWithNext().all { (a, b) -> b >= a }, "Reveal must move smoothly in one direction")
                onPaperUi {
                    assertEquals(editorId, scene.editor().id)
                    assertEquals("Мой вопрос и уточнение", scene.editor().config[SemanticsProperties.EditableText].text)
                    assertEquals(4, scene.editor().config[SemanticsProperties.TextSelectionRange].start)
                    assertTrue(scene.tag("composer-options-panel").boundsInRoot.top >= scene.action("Отправить").boundsInRoot.bottom)
                    assertTrue(scene.semanticsOwners.any { owner ->
                        val ids = descendants(owner.rootSemanticsNode).map { it.id }
                        editorId in ids && scene.tag("composer-options-panel").id in ids
                    }, "Editor and panel belong to the same window, independently of text-selection popups")
                    scene.capture("open-$width-$scale", time)
                    val attachment = scene.nodes().single { it.config.contains(SemanticsActions.OnClick) &&
                        it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Прикрепить файлы" } }
                    attachment.config[SemanticsActions.RequestFocus].action!!.invoke()
                }
                frame()
                onPaperUi {
                    scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown))
                    scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyUp))
                }
                settle()
                onPaperUi {
                    assertEquals(closedHeight, scene.tag("composer-frame").boundsInRoot.height, .5f)
                    assertEquals(true, scene.action("Показать параметры и ресурсы").config[SemanticsProperties.Focused])
                    assertEquals(editorId, scene.editor().id)
                }
                // A second click reverses an opening animation instead of waiting for it.
                toggle(true); repeat(5) { frame() }; toggle(false); settle()
                onPaperUi { assertEquals(closedHeight, scene.tag("composer-frame").boundsInRoot.height, .5f) }
            } finally { onPaperUi { scene.close() } }
        }
    }

    @Test fun reducedMotionSettlesImmediately() {
        val still = object : MotionDurationScale { override val scaleFactor = 0f }
        val scene = onPaperUi { ImageComposeScene(640, 480, coroutineContext = Dispatchers.Unconfined + still) {
            PaperComposerOptionsPreview(initiallyExpanded = false)
        } }
        try {
            onPaperUi {
                repeat(5) { scene.render(it * 16_000_000L).close() }
                scene.action("Показать параметры и ресурсы").config[SemanticsActions.OnClick].action!!.invoke()
                repeat(3) { scene.render((it + 5) * 16_000_000L).close() }
                val height = scene.tag("composer-options-panel").boundsInRoot.height
                assertTrue(height > 0)
                scene.render(500_000_000L).close()
                assertEquals(height, scene.tag("composer-options-panel").boundsInRoot.height)
            }
        } finally { onPaperUi { scene.close() } }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        return semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
    }
    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    private fun ImageComposeScene.action(label: String) = nodes().single {
        it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label)
    }
    private fun ImageComposeScene.editor() = nodes().single { it.config.contains(SemanticsActions.SetText) }
    private fun ImageComposeScene.tag(tag: String) = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private fun ImageComposeScene.capture(name: String, time: Long) {
        File("build/reports/composer-options/$name.png").apply { parentFile.mkdirs() }
            .writeBytes(render(time).use { image -> image.encodeToData()!!.use { it.bytes } })
    }
}
