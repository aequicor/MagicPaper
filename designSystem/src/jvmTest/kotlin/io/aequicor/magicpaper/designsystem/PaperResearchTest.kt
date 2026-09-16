package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.use
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class PaperResearchTest {
    @Test fun followUpPreviewsKeepTheCompleteQuestionAtNarrowAndLargeTextSizes() {
        for (scale in listOf(1f, 2f)) {
            val scene = onPaperUi { ImageComposeScene(if (scale == 1f) 640 else 320, if (scale == 1f) 240 else 900) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) { PaperResearchFollowUpsPreview() }
            } }
            try {
                repeat(6) { onPaperUi { scene.render(it * 32_000_000L).close() } }
                onPaperUi {
                    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                    val nodes = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                    val answer = nodes.single { it.config.getOrNull(SemanticsProperties.Text).orEmpty()
                        .any { text -> text.text == "Начните с небольшой задачи и проверьте результат на практике." } }
                    val questionTexts = listOf("Как выбрать первый проект?", "Сравнить Kotlin и Compose на практическом примере",
                        "Написать статью: от первого экрана до Android-приложения")
                    val questions = nodes.filter { it.config.getOrNull(SemanticsProperties.Text).orEmpty()
                        .any { text -> text.text in questionTexts } }
                    assertEquals(questionTexts.size, questions.size)
                    val questionLayouts = questions.map { question ->
                        assertEquals(answer.boundsInRoot.left, question.boundsInRoot.left, .5f,
                            "Each suggestion shares the answer's leading edge at text scale $scale")
                        val layouts = mutableListOf<TextLayoutResult>()
                        question.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                        layouts.single().also { layout ->
                            assertFalse(layout.hasVisualOverflow)
                            for (line in 0 until layout.lineCount) {
                                assertEquals(0f, layout.getLineLeft(line), .5f,
                                    "Wrapped suggestion lines remain left-aligned at text scale $scale")
                            }
                        }
                    }
                    if (scale == 2f) assertTrue(questionLayouts.any { it.lineCount > 1 },
                        "The large-text fixture exercises wrapping")
                    val directory = java.io.File("build/reports/research-follow-ups").apply { mkdirs() }
                    java.io.File(directory, "questions-$scale.png").writeBytes(scene.render(240_000_000L).use { image -> image.encodeToData()!!.use { it.bytes } })
                }
            } finally { onPaperUi { scene.close() } }
        }
    }

    @Test fun activityPreviewsUseCompactSourcesAndExposeOperationStates() {
        for (scale in listOf(1f, 2f)) {
            val scene = onPaperUi { ImageComposeScene(if (scale == 1f) 440 else 320, if (scale == 1f) 700 else 1600) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    Column {
                        PaperResearchActivityPreview()
                        PaperResearchActivityStatesPreview()
                    }
                }
            } }
            try {
                repeat(8) { onPaperUi { scene.render(it * 32_000_000L).close() } }
                onPaperUi {
                    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                    val nodes = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                    assertTrue(nodes.mapNotNull { it.config.getOrNull(SemanticsProperties.StateDescription) }
                        .containsAll(listOf("Завершено", "Выполняется", "Приостановлено", "Ошибка")))
                    val link = nodes.first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Kotlin Documentation" } }
                    val layouts = mutableListOf<TextLayoutResult>()
                    link.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                    assertEquals(11f, layouts.single().layoutInput.style.fontSize.value)
                    assertEquals(androidx.compose.ui.text.font.FontFamily.SansSerif, layouts.single().layoutInput.style.fontFamily)
                    assertFalse(layouts.single().hasVisualOverflow)
                    val directory = java.io.File("build/reports/research-activity").apply { mkdirs() }
                    java.io.File(directory, "states-$scale.png").writeBytes(scene.render(300_000_000L).use { image -> image.encodeToData()!!.use { it.bytes } })
                }
            } finally { onPaperUi { scene.close() } }
        }
    }

    @Test fun compactResearchRowsExposeSelectionAndKeepLongTitlesToTwoLines() {
        for (scale in listOf(1f, 2f)) {
            val scene = onPaperUi { ImageComposeScene(340, 520) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) { PaperResearchRowsPreview() }
            } }
            try {
                repeat(4) { onPaperUi { scene.render(it * 32_000_000L).close() } }
                onPaperUi {
                    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                    val nodes = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                    val row = nodes.first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { label -> label.startsWith("Вопрос 1:") } == true }
                    assertTrue(row.config[SemanticsProperties.Selected])
                    assertTrue(row.boundsInRoot.height >= 44f)
                    val title = nodes.first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.startsWith("Как организовать") } == true }
                    val layouts = mutableListOf<TextLayoutResult>()
                    title.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                    assertEquals(2, layouts.single().lineCount)
                    assertTrue(nodes.any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { label -> label == "Использовать источник: Отключённый источник" } == true })
                    val directory = java.io.File("build/reports/research-rows").apply { mkdirs() }
                    java.io.File(directory, "rows-$scale.png").writeBytes(scene.render(200_000_000L).use { image -> image.encodeToData()!!.use { it.bytes } })
                }
            } finally { onPaperUi { scene.close() } }
        }
    }

    @Test fun bookTypeStaysDenseAndAlignedWithoutChangingOtherScreens() {
        for (scale in listOf(1f, 2f)) {
            val scene = onPaperUi { ImageComposeScene(390, 900) {
                PaperTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                        Column {
                            PaperText("До страницы")
                            PaperResearchReading {
                                Column(Modifier.paperResearchMessage(true, true, user = true)) { PaperText("Вопрос") }
                                Column(Modifier.paperResearchMessage(true, true, user = false)) {
                                    PaperText("Заголовок", role = PaperTextRole.HEADLINE)
                                    PaperText("Текст ответа: читаемые строки, компактный интервал, общая направляющая.")
                                }
                            }
                            PaperText("После страницы")
                        }
                    }
                }
            } }
            try {
                repeat(8) { onPaperUi { scene.render(it * 32_000_000L).close() } }
                onPaperUi {
                    fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
                    val nodes = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                    fun node(prefix: String) = nodes.first {
                        it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text.startsWith(prefix) }
                    }
                    fun layout(prefix: String): TextLayoutResult {
                        val results = mutableListOf<TextLayoutResult>()
                        node(prefix).config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
                        return results.single()
                    }
                    val body = layout("Текст ответа")
                    val style = body.layoutInput.style
                    assertTrue(style.lineHeight.value / style.fontSize.value in 1.4f..1.55f)
                    assertFalse(body.hasVisualOverflow)
                    assertEquals(node("Вопрос").boundsInRoot.left, node("Текст ответа").boundsInRoot.left)
                    assertEquals(style.fontFamily, layout("Заголовок").layoutInput.style.fontFamily)
                    assertEquals(layout("До страницы").layoutInput.style, layout("После страницы").layoutInput.style)
                    assertTrue(style.fontSize > layout("До страницы").layoutInput.style.fontSize)
                }
            } finally { onPaperUi { scene.close() } }
        }
    }

    @Test fun composerMovesContinuouslyAndKeepsItsEditorIdentity() {
        val centered = mutableStateOf(true)
        val scene = onPaperUi { ImageComposeScene(800, 700) {
            PaperTheme {
                PaperResearchReading {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.align(paperResearchComposerAlignment(centered.value)).width(600.dp)) {
                            PaperPromptField("Сохранённый вопрос", {}, "Вопрос")
                        }
                    }
                }
            }
        } }
        fun editor(): SemanticsNode {
            fun find(node: SemanticsNode): SemanticsNode? = if (node.config.contains(SemanticsActions.SetText)) node
                else node.children.firstNotNullOfOrNull(::find)
            return scene.semanticsOwners.firstNotNullOf { find(it.unmergedRootSemanticsNode) }
        }
        try {
            repeat(8) { onPaperUi { scene.render(it * 32_000_000L).close() } }
            val before = onPaperUi { editor().id to editor().boundsInRoot.top }
            onPaperUi {
                centered.value = false
                scene.render(300_000_000L).close()
                scene.render(340_000_000L).close()
                scene.render(420_000_000L).close()
            }
            val middle = onPaperUi { editor().boundsInRoot.top }
            onPaperUi { scene.render(700_000_000L).close() }
            onPaperUi {
                assertEquals(before.first, editor().id)
                assertTrue(middle > before.second && middle < editor().boundsInRoot.top, "Editor moves through intermediate positions")
                assertEquals("Сохранённый вопрос", editor().config[SemanticsProperties.EditableText].text)
            }
        } finally { onPaperUi { scene.close() } }
    }

    @Test fun researchTextAndSourceSelectionMeetTextContrast() {
        val colors = PaperColors()
        for (surface in listOf(colors.surface, colors.canvas)) {
            for (text in listOf(colors.text, colors.secondaryText)) {
                assertTrue((surface.luminance() + .05f) / (text.luminance() + .05f) >= 4.5f)
            }
        }
        for (surface in listOf(colors.selected, colors.successSurface)) {
            assertTrue((surface.luminance() + .05f) / (colors.text.luminance() + .05f) >= 4.5f)
        }
        val scene = onPaperUi { ImageComposeScene(400, 160) {
            PaperTheme { PaperListRow("Вопрос", secondary = "Выбран", selected = true) }
        } }
        try {
            repeat(8) { onPaperUi { scene.render(it * 32_000_000L).close() } }
            onPaperUi {
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                val selectedLabel = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.first {
                    it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Выбран" }
                }
                val layouts = mutableListOf<TextLayoutResult>()
                selectedLabel.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                val foreground = layouts.single().layoutInput.style.color
                assertTrue((colors.selected.luminance() + .05f) / (foreground.luminance() + .05f) >= 4.5f,
                    "The rendered selected label must remain readable on lilac")
            }
        } finally { onPaperUi { scene.close() } }
    }
}
