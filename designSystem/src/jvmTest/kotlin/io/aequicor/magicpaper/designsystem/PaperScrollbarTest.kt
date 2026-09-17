package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.TextFieldValue
import java.io.File
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class PaperScrollbarTest {
    @Test fun scrollbarShowsForEdgeHoverAndScrollingHidesAtRestAndSupportsDragging() {
        val state = ScrollState(0)
        var compositions = 0
        val scene = onPaperUi { ImageComposeScene(240, 200) { PaperTheme {
            PaperSurface(Modifier.fillMaxSize()) {
                PaperScrollColumn(Modifier.fillMaxSize(), state) {
                    SideEffect { compositions++ }
                    repeat(80) { PaperText("Source $it", Modifier.height(28.dp)) }
                }
            }
        } } }
        var frame = 0L
        fun render() = onPaperUi { repeat(4) { scene.render(++frame * 32_000_000L).close() } }
        fun edge(name: String): IntArray = onPaperUi {
            val bytes = scene.render(++frame * 32_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } }
            File("build/reports/scrollbars/$name.png").apply { parentFile.mkdirs(); writeBytes(bytes) }
            ImageIO.read(ByteArrayInputStream(bytes)).getRGB(230, 0, 10, 200, null, 0, 10)
        }
        fun move(x: Float, y: Float) { onPaperUi { scene.sendPointerEvent(PointerEventType.Move, Offset(x, y), type = PointerType.Mouse) }; render() }
        try {
            render()
            val baseline = edge("idle")
            val count = compositions
            move(238f, 12f)
            assertFalse(baseline.contentEquals(edge("hover")))
            move(120f, 100f)
            assertContentEquals(baseline, edge("left-edge"))
            onPaperUi { runBlocking { state.scrollTo(400) } }
            render()
            assertFalse(baseline.contentEquals(edge("scrolling")))
            Thread.sleep(750); render()
            assertContentEquals(baseline, edge("stopped"))
            assertEquals(count, compositions, "Scrolling and indicator visibility do not recompose the content")
            onPaperUi { runBlocking { state.scrollTo(0) } }
            render(); move(234f, 12f)
            onPaperUi {
                scene.sendPointerEvent(PointerEventType.Press, Offset(234f, 12f), type = PointerType.Mouse, button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Move, Offset(234f, 145f), type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Release, Offset(234f, 145f), type = PointerType.Mouse, button = PointerButton.Primary)
            }
            render()
            assertTrue(state.value > 400, "Dragging the thumb scrolls the actual content")
            edge("dragged")
            val beforeWheel = state.value
            onPaperUi { scene.sendPointerEvent(PointerEventType.Scroll, Offset(234f, 145f), scrollDelta = Offset(0f, 3f), type = PointerType.Mouse) }
            render()
            assertTrue(state.value > beforeWheel, "Wheel input over the scrollbar must reach its viewport")
        } finally { onPaperUi { scene.close() } }
    }

    @Test fun lazyViewportRetainsVirtualizationAfterScrollingFarDown() {
        val state = LazyListState()
        val composed = mutableSetOf<Int>()
        val scene = onPaperUi { ImageComposeScene(240, 200) { PaperTheme {
            PaperLazyColumn(Modifier.fillMaxSize(), state) {
                items(10_000, key = { it }) { index ->
                    SideEffect { composed += index }
                    PaperText("Question $index", Modifier.height(if (index % 2 == 0) 30.dp else 50.dp))
                }
            }
        } } }
        try {
            onPaperUi {
                repeat(5) { scene.render((it + 1) * 32_000_000L).close() }
                assertTrue(composed.size < 20)
                runBlocking { state.scrollToItem(1000) }
                repeat(5) { scene.render((it + 6) * 32_000_000L).close() }
                assertEquals(1000, state.firstVisibleItemIndex)
                assertTrue(composed.size < 40)
            }
        } finally { onPaperUi { scene.close() } }
    }

    @Test fun horizontalScrollbarSupportsDraggingAndPreviewScales() {
        val state = ScrollState(0)
        val scene = onPaperUi { ImageComposeScene(320, 100) { PaperTheme {
            PaperScrollRow(Modifier.fillMaxSize(), state) { PaperText("Long code", Modifier.width(1600.dp)) }
        } } }
        try {
            onPaperUi {
                repeat(5) { scene.render((it + 1) * 32_000_000L).close() }
                scene.sendPointerEvent(PointerEventType.Move, Offset(20f, 94f), type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Press, Offset(20f, 94f), type = PointerType.Mouse, button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Move, Offset(260f, 94f), type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Release, Offset(260f, 94f), type = PointerType.Mouse, button = PointerButton.Primary)
                repeat(5) { scene.render((it + 6) * 32_000_000L).close() }
                assertTrue(state.value > 500, "The horizontal thumb moves the actual viewport")
            }
        } finally { onPaperUi { scene.close() } }
        for (scale in listOf(1f, 2f)) {
            val height = if (scale == 1f) 280 else 360
            val preview = onPaperUi { ImageComposeScene(520, height) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) { PaperScrollPreview() }
            } }
            try { onPaperUi {
                repeat(5) { preview.render((it + 1) * 32_000_000L).close() }
                preview.sendPointerEvent(PointerEventType.Move, Offset(460f, height - 16f), type = PointerType.Mouse)
                repeat(5) { preview.render((it + 6) * 32_000_000L).close() }
                File("build/reports/scrollbars/gallery-$scale.png").apply { parentFile.mkdirs() }.writeBytes(
                    preview.render(400_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })
            } } finally { onPaperUi { preview.close() } }
        }
    }

    @Test fun longMenuHasAScrollbarAndItsLastActionIsReachable() {
        var selected = -1
        val scene = onPaperUi { ImageComposeScene(480, 640) { PaperTheme {
            Box(Modifier.padding(20.dp)) {
                PaperMenuHost(true, {}) {
                    repeat(60) { index -> PaperMenuAction("Option $index", { selected = index }) }
                }
            }
        } } }
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        try { onPaperUi {
            repeat(8) { scene.render((it + 1) * 32_000_000L).close() }
            fun nodes() = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            val bar = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Вертикальная прокрутка") }.boundsInRoot
            val start = Offset(bar.center.x, bar.top + 8f)
            val end = Offset(bar.center.x, bar.bottom - 1f)
            scene.sendPointerEvent(PointerEventType.Move, start, type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Press, start, type = PointerType.Mouse, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Move, end, type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, end, type = PointerType.Mouse, button = PointerButton.Primary)
            repeat(8) { scene.render((it + 9) * 32_000_000L).close() }
            val last = nodes().first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Option 59" } }.boundsInRoot
            assertTrue(last.top >= bar.top && last.bottom <= bar.bottom, "The last menu action must scroll fully into view: $last, viewport=$bar")
            scene.sendPointerEvent(PointerEventType.Press, last.center, type = PointerType.Mouse, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, last.center, type = PointerType.Mouse, button = PointerButton.Primary)
            assertEquals(59, selected)
            File("build/reports/scrollbars/menu.png").apply { parentFile.mkdirs() }.writeBytes(
                scene.render(600_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })
        } } finally { onPaperUi { scene.close() } }
    }

    @Test fun shortContentHasNoScrollbarAndTextFieldScrollingDoesNotEditTheDraft() {
        var edits = 0
        val draft = TextFieldValue((1..60).joinToString("\n") { "Line $it" })
        val scene = onPaperUi { ImageComposeScene(320, 240) { PaperTheme {
            PaperSurface(Modifier.fillMaxSize()) {
                Column {
                    PaperScrollColumn(Modifier.fillMaxWidth().height(60.dp)) { PaperText("Fits") }
                    PaperPromptField(draft, { edits++ }, "Draft", Modifier.fillMaxWidth().height(160.dp), maxLines = 5)
                }
            }
        } } }
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        try {
            onPaperUi {
                repeat(6) { scene.render((it + 1) * 32_000_000L).close() }
                val bars = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.filter {
                    it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Вертикальная прокрутка") }
                assertEquals(1, bars.size, "Only the overflowing editor has a scrollbar")
                val rect = bars.single().boundsInRoot
                val start = Offset(rect.center.x, rect.top + 8f)
                val end = Offset(rect.center.x, rect.bottom - 8f)
                scene.sendPointerEvent(PointerEventType.Move, start, type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Press, start, type = PointerType.Mouse, button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Move, end, type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Release, end, type = PointerType.Mouse, button = PointerButton.Primary)
                repeat(6) { scene.render((it + 7) * 32_000_000L).close() }
                assertEquals(0, edits, "Scrollbar dragging must not modify the text or selection")
                File("build/reports/scrollbars/editor.png").writeBytes(scene.render(450_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })
            }
        } finally { onPaperUi { scene.close() } }
    }
}
