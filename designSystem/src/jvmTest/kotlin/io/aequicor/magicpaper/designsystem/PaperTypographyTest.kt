package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.use
import kotlinx.coroutines.runBlocking
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class PaperTypographyTest {
    @Test fun agentParagraphsAndListsUseTheSameFontAsUserText() = runBlocking {
        val content = "Одинаковый текст\n\n- Элемент списка\n\n1. Нумерованный пункт\n\n| Колонка |\n| --- |\n| Значение |"
        val document = parsePaperMarkdown(content)
        var expected = TextStyle.Default
        ImageComposeScene(600, 700) {
            PaperTheme {
                expected = LocalPaperTypography.current.body
                Column {
                    PaperText("Сообщение пользователя")
                    PaperMarkdownBody(document, document.node.children)
                    PaperMarkdown("Простой Markdown")
                }
            }
        }.use { scene ->
            repeat(20) { scene.render((it + 1) * 16_000_000L).close() }
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            val layouts = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.flatMap { node ->
                val results = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                results
            }
            for (label in listOf("Сообщение пользователя", "Одинаковый текст", "Элемент списка", "Нумерованный пункт", "Значение", "Простой Markdown")) {
                val layout = assertNotNull(layouts.firstOrNull { label in it.layoutInput.text.text }, label)
                assertEquals(expected.fontSize, layout.layoutInput.style.fontSize, label)
                assertEquals(expected.fontFamily, layout.layoutInput.style.fontFamily, label)
                assertEquals(expected.lineHeight, layout.layoutInput.style.lineHeight, label)
            }
        }
    }
}
