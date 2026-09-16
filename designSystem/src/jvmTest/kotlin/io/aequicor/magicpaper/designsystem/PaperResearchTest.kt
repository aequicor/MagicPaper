package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class PaperResearchTest {
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
