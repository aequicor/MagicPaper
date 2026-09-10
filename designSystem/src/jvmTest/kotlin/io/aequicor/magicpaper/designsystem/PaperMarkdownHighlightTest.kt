package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import java.awt.EventQueue
import kotlinx.coroutines.runBlocking
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalComposeUiApi::class)
class PaperMarkdownHighlightTest {
    @Test
    fun concurrentCodeBlocksKeepTheirOwnTextAndHighlightRanges() {
        val fixture = fixture(revision = 0)
        renderFixture(fixture) { scene, render ->
            awaitHighlighted(scene, fixture, render)
        }
    }

    @Test
    fun streamedCodeCanGrowAndShortenWhilePreviousHighlightingFinishes() {
        val revisions = (0..8).map(::fixture)
        val current = mutableStateOf(revisions.first())
        val scene = onUi {
            ImageComposeScene(720, 640) {
                PaperTheme {
                    PaperMarkdownBody(current.value.document, current.value.nodes,
                        Modifier.verticalScroll(rememberScrollState()))
                }
            }
        }
        var frame = 0L
        fun render() = onUi { scene.render(++frame * 16_000_000L).close() }
        try {
            awaitHighlighted(scene, revisions.first(), ::render)
            // Keep the composition alive and replace the source before the previous
            // Dispatchers.Default work is required to finish. Odd revisions shrink it.
            for (fixture in revisions.drop(1)) {
                onUi { current.value = fixture }
                render()
            }
            awaitHighlighted(scene, revisions.last(), ::render)
            onUi { current.value = revisions[1] }
            awaitHighlighted(scene, revisions[1], ::render)
        } finally {
            onUi { scene.close() }
        }
    }

    private data class ExpectedCode(val text: String, val styles: List<AnnotatedString.Range<SpanStyle>>)
    private data class Fixture(
        val document: PaperMarkdownDocument,
        val nodes: List<ASTNode>,
        val codes: List<ExpectedCode>,
    )

    private class CodeSlice(
        override val original: ASTNode,
        override val code: IntRange,
    ) : ASTNode by original, PaperMarkdownSlice {
        override val listNumber: Int? = null
        override val listContinuation: Boolean = false
    }

    private fun fixture(revision: Int): Fixture = runBlocking {
        val lengths = listOf(1332, 1333, 96, 8192, 257, 4096)
        val codes = List(24) { index ->
            val length = if (revision % 2 == 0) lengths[index % lengths.size] else 80 + index * 7
            code(length, "${revision}_$index")
        }
        val source = codes.mapIndexed { index, code ->
            val sliced = index % 4 >= 2
            val content = if (sliced) "// omitted prefix\n$code\n// omitted suffix" else code
            val block = if (index % 4 == 1 || index % 4 == 3)
                content.prependIndent("    ") else "```kotlin\n$content\n```"
            "Block $index\n\n$block"
        }.joinToString("\n\n")
        val document = parsePaperMarkdown(source)
        var index = 0
        val nodes = document.node.children.map { node ->
            if (node.type != MarkdownElementTypes.CODE_FENCE && node.type != MarkdownElementTypes.CODE_BLOCK) node
            else {
                val codeIndex = index++
                if (codeIndex % 4 < 2) node else {
                    val start = source.indexOf(codes[codeIndex], node.startOffset)
                    check(start >= node.startOffset && start + codes[codeIndex].length <= node.endOffset)
                    CodeSlice(node, start until start + codes[codeIndex].length)
                }
            }
        }
        assertEquals(codes.size, index, "All fixtures must be parsed as independent code blocks")
        Fixture(document, nodes, codes.mapIndexed { i, text ->
            ExpectedCode(text, expectedStyles(text, kotlin = i % 2 == 0))
        })
    }

    private fun code(length: Int, id: String): String {
        val prefix = "val sample$id = 1; "
        val statement = "println(42); "
        val remaining = length - prefix.length - 3
        return prefix + statement.repeat(remaining / statement.length) + "// " + "x".repeat(remaining % statement.length)
    }

    /** Isolated sequential reference, independent of the renderer's asynchronous builders. */
    private fun expectedStyles(text: String, kotlin: Boolean): List<AnnotatedString.Range<SpanStyle>> {
        val builder = Highlights.Builder().theme(SyntaxThemes.default(darkMode = false)).code(text)
        if (kotlin) builder.language(SyntaxLanguage.getByName("kotlin")!!)
        return builder.build().getHighlights().map { highlight ->
            val style = when (highlight) {
                is ColorHighlight -> SpanStyle(color = Color(highlight.rgb).copy(alpha = 1f))
                is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
            }
            AnnotatedString.Range(style, highlight.location.start, highlight.location.end)
        }
    }

    private fun renderFixture(fixture: Fixture, block: (ImageComposeScene, () -> Unit) -> Unit) {
        val scene = onUi {
            ImageComposeScene(720, 640) {
                PaperTheme {
                    PaperMarkdownBody(fixture.document, fixture.nodes,
                        Modifier.verticalScroll(rememberScrollState()))
                }
            }
        }
        var frame = 0L
        try {
            block(scene) { onUi { scene.render(++frame * 16_000_000L).close() } }
        } finally {
            onUi { scene.close() }
        }
    }

    private fun awaitHighlighted(scene: ImageComposeScene, fixture: Fixture, render: () -> Unit) {
        val deadline = System.nanoTime() + 15_000_000_000L
        var texts = emptyList<AnnotatedString>()
        while (System.nanoTime() < deadline) {
            render()
            texts = onUi { scene.layoutTexts() }
            for (text in texts) for (span in text.spanStyles) {
                assertTrue(span.start >= 0 && span.end >= span.start && span.end <= text.length,
                    "Highlight [${span.start}, ${span.end}) exceeds text length ${text.length}")
            }
            val ready = fixture.codes.all { expected ->
                texts.any { it.text == expected.text &&
                    (expected.styles.isEmpty() || it.spanStyles.isNotEmpty()) }
            }
            if (ready) {
                for ((index, expected) in fixture.codes.withIndex()) {
                    val actual = texts.single { it.text == expected.text }
                    assertEquals(expected.styles, actual.spanStyles,
                        "Block $index (${expected.text.length} chars) must retain its own syntax ranges")
                }
                return
            }
            Thread.sleep(10)
        }
        val missing = fixture.codes.filter { expected -> texts.none { it.text == expected.text && it.spanStyles.isNotEmpty() } }
        fail("Syntax highlighting did not finish for ${missing.map { it.text.take(28) }}; laid out ${texts.size} texts")
    }

    private fun ImageComposeScene.layoutTexts(): List<AnnotatedString> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.flatMap { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            layouts.map { it.layoutInput.text }
        }
    }

    private fun <T> onUi(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
