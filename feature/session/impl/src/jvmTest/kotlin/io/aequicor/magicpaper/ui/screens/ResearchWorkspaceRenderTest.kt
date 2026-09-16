package io.aequicor.magicpaper.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import java.awt.EventQueue
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class ResearchWorkspaceRenderTest {
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
    private fun ImageComposeScene.action(label: String) = nodes().first { node ->
        node.config.contains(SemanticsActions.OnClick) &&
            (node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == label } ||
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } ||
                node.children.any { child -> child.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } })
    }
    private fun ImageComposeScene.capture(name: String, frame: Long) = onUi {
        val directory = File("build/reports/research-workspace").apply { mkdirs() }
        File(directory, "$name.png").writeBytes(render(frame).use { image -> image.encodeToData()!!.use { it.bytes } })
    }

    @Test fun workspaceUsesOnlyPlatformSaveableLazyListKeys() {
        fun platformSaveable(value: Any?): Boolean = when (value) {
            null, is String, is Boolean, is Number, is Char -> true
            is MutableState<*> -> platformSaveable(value.value)
            is List<*> -> value.all(::platformSaveable)
            is Array<*> -> value.all(::platformSaveable)
            is Map<*, *> -> value.all { (key, item) -> platformSaveable(key) && platformSaveable(item) }
            else -> false
        }
        val registry = SaveableStateRegistry(restoredValues = null, canBeSaved = ::platformSaveable)
        val scene = onUi { ImageComposeScene(1280, 850) {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                ResearchWorkspacePreview()
            }
        } }
        try {
            repeat(3) { onUi { scene.render(it * 32_000_000L).close() } }
            onUi { registry.performSave() }
        } finally { onUi { scene.close() } }
    }

    @Test fun galleryKeepsReadingAndActionsVisibleAtNarrowAndLargeTextSizes() {
        data class Case(val name: String, val width: Int, val height: Int, val scale: Float = 1f)
        for (case in listOf(Case("reading", 1280, 850), Case("empty", 1280, 850),
            Case("narrow", 390, 780), Case("empty-narrow", 390, 780), Case("large-text", 720, 1000, 2f),
            Case("working", 1280, 850), Case("error", 390, 780))) {
            val empty = case.name.startsWith("empty")
            val scene = onUi { ImageComposeScene(case.width, case.height) {
                CompositionLocalProvider(LocalDensity provides Density(1f, case.scale)) {
                    ResearchWorkspacePreview(empty, busy = case.name == "working", failed = case.name == "error")
                }
            } }
            try {
                repeat(24) { onUi { scene.render(it * 32_000_000L).close() }; Thread.sleep(5) }
                onUi {
                    for (label in listOf("Новый вопрос", if (case.name == "working") "Пауза" else "Отправить")) {
                        val bounds = scene.action(label).boundsInRoot
                        assertTrue(bounds.left >= 0 && bounds.right <= case.width, "$label clipped in ${case.name}")
                        assertTrue(bounds.top >= 0 && bounds.bottom <= case.height, "$label outside ${case.name}")
                    }
                    if (case.width >= 1180) {
                        assertTrue(scene.text("Вопросы").boundsInRoot.left >= 0)
                        assertTrue(scene.text("Источники").boundsInRoot.right <= case.width)
                    } else {
                        for (label in listOf("Вопросы (${if (empty) 1 else 3})", "Источники (${if (empty) 0 else 4})")) {
                            val bounds = scene.action(label).boundsInRoot
                            assertTrue(bounds.left >= 0 && bounds.right <= case.width, "$label clipped in ${case.name}")
                        }
                    }
                    val input = scene.nodes().first { node -> node.config.contains(SemanticsActions.SetText) &&
                        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.contains(if (empty) "Сформулируйте" else "Уточните") } }
                    if (empty) {
                        assertTrue(input.boundsInRoot.center.y in (case.height * .3f)..(case.height * .75f))
                        assertTrue(scene.text("Что будем исследовать?").boundsInRoot.bottom < input.boundsInRoot.top)
                    }
                    if (case.width >= 1180) assertTrue(input.boundsInRoot.right < scene.text("Источники").boundsInRoot.left)
                }
                scene.capture(case.name, 900_000_000L)
            } finally { onUi { scene.close() } }
        }
    }

    @Test fun sourceMenuOffersValidationAndClosingWithoutLosingComposerDraft() {
        val scene = onUi { ImageComposeScene(720, 850) { ResearchEmptyPreview() } }
        var frame = 0L
        fun render() { repeat(16) { onUi { scene.render(++frame * 32_000_000L).close() }; Thread.sleep(5) } }
        try {
            render()
            onUi {
                scene.nodes().first { it.config.contains(SemanticsActions.SetText) }
                    .config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Мой исследовательский вопрос"))
                scene.action("Источники (0)").config[SemanticsActions.OnClick].action!!.invoke()
            }
            render()
            onUi { scene.action("Добавить ссылку").config[SemanticsActions.OnClick].action!!.invoke() }
            render()
            onUi {
                scene.nodes().last { it.config.contains(SemanticsActions.SetText) }
                    .config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("не ссылка"))
            }
            render()
            onUi { scene.action("Добавить").config[SemanticsActions.OnClick].action!!.invoke() }
            render()
            onUi { assertTrue(scene.text("Введите ссылку: https://…").boundsInRoot.height > 0) }
            scene.capture("source-validation", ++frame * 32_000_000L)
            onUi { scene.action("Скрыть источники").config[SemanticsActions.OnClick].action!!.invoke() }
            render()
            onUi {
                val input = scene.nodes().first { it.config.contains(SemanticsActions.SetText) }
                assertEquals("Мой исследовательский вопрос", input.config[SemanticsProperties.EditableText].text)
            }
        } finally { onUi { scene.close() } }
    }
}
