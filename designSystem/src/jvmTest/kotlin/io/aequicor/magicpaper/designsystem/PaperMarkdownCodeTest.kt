package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aequicor.magicpaper.ui.components.ChatMarkdownDocuments
import kotlinx.coroutines.runBlocking
import org.intellij.markdown.MarkdownElementTypes as Element
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class PaperMarkdownCodeTest {
    @Test fun smallSyntaxTextKeepsContrastOnPaperCodeSurfaces() {
        val theme = paperCodeSyntaxTheme
        val colors = PaperColors()
        // Include the darker message/card surface, not just the light article canvas.
        for (surface in listOf(colors.surface, colors.raisedSurface, colors.agentMessageSurface, Color(0xFFEAE7E5))) {
            for (rgb in listOf(theme.code, theme.keyword, theme.string, theme.literal, theme.comment,
                theme.metadata, theme.multilineComment, theme.punctuation, theme.mark)) {
                val foreground = Color(rgb).copy(alpha = 1f)
                assertTrue((surface.luminance() + .05f) / (foreground.luminance() + .05f) >= 4.5f, "Syntax color $foreground on $surface")
            }
        }
    }

    @Test fun metadataSeparatesLanguageFromQuotedOrUnquotedFilename() {
        for (key in listOf("title", "filename", "file")) {
            assertEquals(PaperCodeInfo("kotlin", "src/main/Example.kt"), paperCodeInfo("kotlin $key=src/main/Example.kt"))
            assertEquals(PaperCodeInfo("yaml", "my project/application.yml"), paperCodeInfo("yaml $key=\"my project/application.yml\""))
        }
        assertEquals(PaperCodeInfo(null, "a.kt"), paperCodeInfo("title='a.kt'"))
        assertEquals(PaperCodeInfo("kotlin", null), paperCodeInfo("kotlin"))
        assertNull(paperCodeInfo("kotlin title=\"bad\npath\"").title)
    }

    @Test fun fileCaptionMovesOnlyWhenItIsUnambiguousAndImmediatelyBeforeCode() = runBlocking {
        for (caption in listOf("`src/main/resources/application.yml`:", "build.gradle.kts", "`Dockerfile`:")) {
            val source = "$caption\n\n```yaml\na: 1\n```"
            val doc = parsePaperMarkdown(source)
            assertEquals(source, doc.source, "Rendering must not change saved Markdown")
            assertTrue(doc.node.children.none { it.type == Element.PARAGRAPH })
            assertEquals(caption.trim('`', ':').trim('`'), (doc.node.children.single { it.type == Element.CODE_FENCE } as PaperNamedCodeNode).title)
        }
        for (caption in listOf("Измените `application.yml`:", "https://example.com/a.kt", "[application.yml](https://example.com)", "Настройки:", "example.com contains details")) {
            val doc = parsePaperMarkdown("$caption\n\n```yaml\na: 1\n```")
            assertTrue(doc.node.children.any { it.type == Element.PARAGRAPH }, caption)
        }
        val conflict = parsePaperMarkdown("`other.kt`:\n\n```kotlin title=Main.kt\nval x = 1\n```")
        assertTrue(conflict.node.children.any { it.type == Element.PARAGRAPH })
        val separated = parsePaperMarkdown("`Main.kt`:\n\nSome explanation.\n\n```kotlin\nval x = 1\n```")
        assertTrue(separated.node.children.none { it is PaperNamedCodeNode })
    }

    @Test fun renderingRunsKeepNormalStatementsIntactAndBoundPathologicalLines() {
        val lines = (1..1000).joinToString("\n") { "val value$it = \"" + "sample ".repeat(30) + "\"" }
        val ranges = paperCodeRanges(lines)
        assertEquals(lines, ranges.joinToString("") { lines.substring(it) })
        assertTrue(ranges.dropLast(1).all { lines[it.last] == '\n' }, "Normal statements may only be split at actual line endings")
        for (source in listOf("😀".repeat(10000), "x\n".repeat(10000))) {
            val parts = paperCodeRanges(source)
            assertEquals(source, parts.joinToString("") { source.substring(it) })
            assertTrue(parts.all { it.count() <= 4096 && !source[it.last].isHighSurrogate() })
        }
    }

    @Test fun mediumCodeKeepsOneHeaderSmallTypeAndCopiesTheWholeFile() {
        val code = (1..48).joinToString("\n") { "val value$it = $it // line beyond the old 32-line split" }
        val source = "`src/main/kotlin/Example.kt`:\n\n```kotlin\n$code\n```"
        val doc = runBlocking { ChatMarkdownDocuments.load(source, cache = false) }
        val clipboard = MemoryClipboard()
        val scene = onPaperUi { ImageComposeScene(720, 900) {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) { PaperTheme {
                PaperSurface(Modifier.fillMaxSize()) {
                    PaperMarkdownBody(doc.document, doc.blocks, Modifier.padding(16.dp))
                }
            } }
        } }
        try {
            settle(scene) { scene.copyButtons().size == 1 && scene.layouts().any { it.layoutInput.text.text == code } }
            onPaperUi {
                val layout = scene.layouts().single { it.layoutInput.text.text == code }
                assertEquals(10.sp, layout.layoutInput.style.fontSize)
                assertEquals(14.sp, layout.layoutInput.style.lineHeight)
                assertEquals(1, scene.layouts().count { it.layoutInput.text.text == "src/main/kotlin/Example.kt" })
                scene.copyButtons().single().config[SemanticsActions.OnClick].action!!.invoke()
                assertEquals(code, clipboard.copied?.text)
                scene.capture("medium-one-card")
            }
        } finally { onPaperUi { scene.close() } }
    }

    @Test fun thousandLineCodeLaysOutOnlyVisibleRunsAndCanReachItsEnd() {
        val code = (1..1000).joinToString("\n") { "val value$it = $it // " + "wide ".repeat(35) }
        val doc = runBlocking { ChatMarkdownDocuments.load("```kotlin title=Example.kt\n$code\n```", cache = false) }
        val clipboard = MemoryClipboard()
        val scene = onPaperUi { ImageComposeScene(480, 540) {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) { PaperTheme {
                PaperSurface(Modifier.fillMaxSize()) {
                    PaperMarkdownBody(doc.document, doc.blocks, Modifier.padding(16.dp))
                }
            } }
        } }
        try {
            settle(scene) { scene.copyButtons().size == 1 && scene.layouts().any { "value1 =" in it.layoutInput.text.text } }
            onPaperUi {
                val visibleChars = scene.layouts().sumOf { it.layoutInput.text.length }
                assertTrue(visibleChars < code.length / 4, "Long code must not lay out the entire file: $visibleChars of ${code.length}")
                scene.copyButtons().single().config[SemanticsActions.OnClick].action!!.invoke()
                assertEquals(code, clipboard.copied?.text)
                val vertical = scene.nodes().single { it.config.contains(SemanticsActions.ScrollToIndex) }
                val last = paperCodeRanges(code).lastIndex
                vertical.config[SemanticsActions.ScrollToIndex].action!!.invoke(last)
            }
            settle(scene) { scene.layouts().any { "value1000 =" in it.layoutInput.text.text } }
            onPaperUi {
                assertEquals(1, scene.copyButtons().size)
                assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.maxValue()?.let { max -> max > 0f } == true })
                scene.capture("long-code-end")
            }
        } finally { onPaperUi { scene.close() } }
    }

    @Test fun filenameHeaderRendersInsideTheCardAtNarrowAndLargeTextSizes() {
        for ((width, scale) in listOf(640 to 1f, 320 to 1f, 480 to 2f)) {
            val scene = onPaperUi { ImageComposeScene(width, 480) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) { PaperMarkdownCodePreview() }
            } }
            try {
                settle(scene) { scene.copyButtons().size == 1 && scene.layouts().any { "spring:" in it.layoutInput.text.text } }
                onPaperUi {
                    val title = scene.textNode("src/main/resources/application.yml")
                    val copy = scene.copyButtons().single()
                    assertTrue(title.boundsInRoot.right <= copy.boundsInRoot.left)
                    val body = scene.nodes().first { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { "spring:" in it.text } }
                    assertTrue(body.boundsInRoot.top >= copy.boundsInRoot.bottom)
                    val layout = scene.layouts().single { "spring:" in it.layoutInput.text.text }
                    assertEquals(10.sp, layout.layoutInput.style.fontSize)
                    scene.capture("filename-$width-$scale")
                }
            } finally { onPaperUi { scene.close() } }
        }
    }

    @Test fun shortCodeSequenceStaysCompactAndKeepsEveryCopyActionAccessible() {
        for ((width, scale) in listOf(640 to 1f, 320 to 1f, 480 to 2f)) {
            val clipboard = MemoryClipboard()
            val scene = onPaperUi { ImageComposeScene(width, 560) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale), LocalClipboardManager provides clipboard) {
                    PaperMarkdownCodeSequencePreview()
                }
            } }
            try {
                settle(scene) { scene.copyButtons().size == 3 && scene.layouts().any { "List выполняет" in it.layoutInput.text.text } }
                onPaperUi {
                    val copies = scene.copyButtons().sortedBy { it.boundsInRoot.top }
                    val code = scene.textNode("./gradlew bootRun")
                    // Adjacent fences share Markdown's gap, without stacking two card margins.
                    assertTrue(copies[1].boundsInRoot.top - code.boundsInRoot.bottom <= 16f)
                    for (copy in copies) {
                        assertTrue(copy.boundsInRoot.width >= 28f && copy.boundsInRoot.height >= 28f)
                        assertTrue(copy.boundsInRoot.right <= width && copy.boundsInRoot.bottom < 560f)
                        copy.config[SemanticsActions.OnClick].action!!.invoke()
                    }
                    assertEquals("{\n  \"answer\": \"List выполняет цепочку преобразований…\"\n}", clipboard.copied?.text)
                    scene.capture("sequence-$width-$scale")
                }
            } finally { onPaperUi { scene.close() } }
        }
    }

    private class MemoryClipboard : ClipboardManager {
        var copied: AnnotatedString? = null
        override fun getText(): AnnotatedString? = copied
        override fun setText(annotatedString: AnnotatedString) { copied = annotatedString }
    }
    private fun settle(scene: ImageComposeScene, ready: () -> Boolean) {
        val end = System.nanoTime() + 15_000_000_000L
        var time = 0L
        while (System.nanoTime() < end) {
            val done = onPaperUi { scene.render(time.also { time += 32_000_000L }).close(); ready() }
            if (done) { repeat(12) { onPaperUi { scene.render(time.also { time += 32_000_000L }).close() } }; return }
            Thread.sleep(10)
        }
        fail("Markdown did not reach the expected rendered state")
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.copyButtons() = nodes().filter {
        it.config.contains(SemanticsActions.OnClick) && "Копировать код" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }
    private fun ImageComposeScene.textNode(text: String) = nodes().first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } }
    private fun ImageComposeScene.layouts(): List<TextLayoutResult> = nodes().flatMap { node ->
        mutableListOf<TextLayoutResult>().also { node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(it) }
    }
    private fun ImageComposeScene.capture(name: String) {
        File("build/reports/markdown-code/$name.png").apply { parentFile.mkdirs() }
            .writeBytes(render().use { it.encodeToData()!!.use { it.bytes } })
    }
}
