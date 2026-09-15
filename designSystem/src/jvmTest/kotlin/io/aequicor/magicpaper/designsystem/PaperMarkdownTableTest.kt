package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import java.awt.EventQueue
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression guard for GFM tables: the library default ellipsizes every cell to a
 * single line, while Paper tables must show their full content wrapped in columns.
 */
@OptIn(ExperimentalComposeUiApi::class)
class PaperMarkdownTableTest {
    private val headers = listOf("Вариант", "Суть", "Плюсы", "Минусы / риск")
    private val firstRow = listOf(
        "A. In-process",
        "агент управляет тем экземпляром приложения, в котором открыт диалог",
        "минимум кода, реальный доступ ко всем сервисам приложения",
        "агент меняет собственное окружение во время работы",
    )
    private val secondRow = listOf(
        "B. Out-of-process",
        "агент запускает изолированную копию и общается с ней по локальному протоколу",
        "реальный GUI, чистая граница состояния приложения",
        "второй процесс: свой жизненный цикл и обновления",
    )
    private val source = buildString {
        appendLine(headers.joinToString(" | ", prefix = "| ", postfix = " |"))
        appendLine(headers.joinToString(" | ", prefix = "| ", postfix = " |") { "---" })
        appendLine(firstRow.joinToString(" | ", prefix = "| ", postfix = " |"))
        append(secondRow.joinToString(" | ", prefix = "| ", postfix = " |"))
    }
    private val wrappedCells = firstRow.drop(1) + secondRow.drop(1)

    @Test
    fun documentBodyTablesWrapCellsInsteadOfEllipsizing() {
        val document = runBlocking { parsePaperMarkdown(source) }
        renderTable {
            PaperMarkdownBody(document, document.node.children, Modifier.verticalScroll(rememberScrollState()))
        }
    }

    @Test
    fun chatMarkdownTablesWrapCellsInsteadOfEllipsizing() {
        renderTable { PaperMarkdown(source) }
    }

    private fun renderTable(content: @Composable () -> Unit) {
        val scene = onUi { ImageComposeScene(720, 640) { PaperTheme { content() } } }
        try {
            var frame = 0L
            onUi { scene.render(++frame * 16_000_000L).close() }
            onUi { scene.render(++frame * 16_000_000L).close() }
            val layouts = onUi { scene.cellLayouts() }
            for (cell in headers + firstRow + secondRow) {
                val matches = layouts.filter { it.layoutInput.text.text.normalize() == cell.normalize() }
                assertEquals(1, matches.size, "Cell must be laid out exactly once: ${cell.take(24)}")
                assertTrue(!matches.single().hasVisualOverflow, "Cell text is clipped: ${cell.take(24)}")
            }
            for (cell in wrappedCells) {
                val layout = layouts.single { it.layoutInput.text.text.normalize() == cell.normalize() }
                assertTrue(layout.lineCount > 1, "Long cell must wrap instead of truncating: ${cell.take(24)}")
            }
        } finally {
            onUi { scene.close() }
        }
    }

    private fun String.normalize() = trim().replace(Regex("\\s+"), " ")

    private fun ImageComposeScene.cellLayouts(): List<TextLayoutResult> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.flatMap { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            layouts
        }
    }

    private fun <T> onUi(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
