package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingSession
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.CodingUi
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Real lazy layout and pointer input, including both sticky levels and animation frames. */
@OptIn(ExperimentalComposeUiApi::class)
class ProjectsPanelStickyTest {
    private class Panel(val precedingProjects: Int = 0) : AutoCloseable {
        val project = CodingProject("a", "magicpaper", "/fixture/a", 0)
        val projects = (0 until precedingProjects).map { CodingProject("before-$it", "Before $it", "/fixture/before-$it", 0) } +
            project + (0..12).map { CodingProject("after-$it", "After $it", "/fixture/after-$it", 0) }
        val ui = mutableStateOf(CodingUi(projects = projects, current = project, sessions = buildList {
            repeat(2) { group ->
                add(CodingSessionUi(CodingSession("plan-$group", "a", "Plan $group", 0, planningMode = true)))
                repeat(16) { child ->
                    add(CodingSessionUi(CodingSession("child-$group-$child", "a", "Stage $group/$child", 0, parentSessionId = "plan-$group")))
                }
            }
            add(CodingSessionUi(CodingSession("ordinary", "a", "Ordinary session", 0)))
        }))
        val list = LazyListState()
        private var frame = 0L
        private val scene = ImageComposeScene(390, 500) {
            MagicPaperTheme {
                ProjectsPanel(ui.value, {}, { id -> ui.value = ui.value.copy(current = projects.single { it.id == id }) }, {},
                    { id -> ui.value = ui.value.copy(currentSessionId = id) }, {}, {}, {},
                    modifier = Modifier.background(Color(0xFFE8DDC8)), listState = list)
            }
        }
        init { render() }
        fun render(frames: Int = 12) { repeat(frames) { scene.render(++frame * 16_000_000L).close(); Thread.sleep(10) } }
        fun item(index: Int, offset: Int = 0) { list.requestScrollToItem(index, offset); render(20) }
        fun projectInfo() = list.layoutInfo.visibleItemsInfo.single { it.key == "project-a" }
        private fun nodes(): List<SemanticsNode> {
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.filter { it.boundsInRoot.height > 0 }
        }
        fun text(value: String): SemanticsNode = nodes().single {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true &&
                    it.boundsInRoot.height > 0
        }
        fun disclosure() = nodes().single {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Свернуть этапы") == true
        }
        fun click(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, point)
            scene.sendPointerEvent(PointerEventType.Release, point)
            render(20)
        }
        fun wheel(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Scroll, point, scrollDelta = Offset(0f, 3f))
            render(30)
        }
        fun png(): ByteArray = scene.render(++frame * 16_000_000L).use {
            it.encodeToData()!!.use { data -> data.bytes }
        }
        fun snapshot(name: String) {
            File("build/reports/project-sticky").apply { mkdirs() }.resolve("$name.png").writeBytes(png())
        }
        fun surfacePixel(): Int = ImageIO.read(ByteArrayInputStream(png())).getRGB(2, 52)
        override fun close() = scene.close()
    }

    @Test fun projectRemainsAbovePinnedSessionAndBothHeadersStayClickable() = Panel(precedingProjects = 2).use { p ->
        p.item(p.precedingProjects)
        val height = p.projectInfo().size
        p.item(p.precedingProjects + 8, 11)
        assertEquals(0, p.projectInfo().offset, "Session headers must never displace their project")
        assertEquals(height, p.projectInfo().size, "Pinning must preserve row height")
        val title = p.text("magicpaper").boundsInRoot
        val session = p.text("Plan 0").boundsInRoot
        assertTrue(title.top >= 49f)
        assertTrue(session.top >= 49f + height, "Session must be below its project")
        p.snapshot("two-level-pinned")
        p.click(session.center)
        assertEquals("plan-0", p.ui.value.currentSessionId)
        p.click(title.center)
        assertTrue(p.list.layoutInfo.visibleItemsInfo.none { it.key.toString().startsWith("session-") })
        assertEquals("plan-0", p.ui.value.currentSessionId, "Collapsing preserves the active session")
    }

    @Test fun sessionChangesAtGroupBoundaryWithoutMovingProject() = Panel().use { p ->
        p.item(10)
        val title = p.text("magicpaper").boundsInRoot
        p.item(25)
        assertEquals(title, p.text("magicpaper").boundsInRoot)
        p.click(p.text("Plan 1").boundsInRoot.center)
        assertEquals("plan-1", p.ui.value.currentSessionId)
        p.snapshot("second-session")
        // After both 17-row groups, a normal session must not inherit Plan 1's overlay.
        p.item(35, -p.projectInfo().size)
        p.click(p.text("Ordinary session").boundsInRoot.center)
        assertEquals("ordinary", p.ui.value.currentSessionId)
        p.item(37)
        assertTrue(p.list.layoutInfo.visibleItemsInfo.none { it.key == "project-a" })
    }

    @Test fun pinAndReleaseAnimateWithoutChangingListGeometry() = Panel().use { p ->
        val height = p.projectInfo().size
        val unpinned = p.surfacePixel()
        p.list.dispatchRawDelta(1f)
        p.render(2)
        val start = p.surfacePixel()
        p.render(4)
        val middle = p.surfacePixel()
        p.render(20)
        val pinned = p.surfacePixel()
        assertNotEquals(unpinned, pinned, "Pinned surface must become opaque")
        assertNotEquals(start, middle, "Pinning needs intermediate animation frames")
        assertNotEquals(middle, pinned, "The transition must not snap to its final frame")
        assertEquals(height, p.projectInfo().size)
        assertEquals(1, p.list.firstVisibleItemScrollOffset, "Animation must not shift the scroll anchor")
        p.list.dispatchRawDelta(-1f)
        p.render(20)
        assertEquals(unpinned, p.surfacePixel(), "Returning to the top restores the original surface")
        assertEquals(height, p.projectInfo().size)
        p.snapshot("returned-to-top")
    }

    @Test fun scrollingOverPinnedSessionAndCollapsingItsChildrenStillWork() = Panel().use { p ->
        p.item(9)
        val before = p.list.firstVisibleItemIndex to p.list.firstVisibleItemScrollOffset
        p.wheel(p.text("Plan 0").boundsInRoot.center)
        assertNotEquals(before, p.list.firstVisibleItemIndex to p.list.firstVisibleItemScrollOffset,
            "The overlay must forward scrolling to the list")
        p.click(p.disclosure().boundsInRoot.center)
        assertTrue(p.list.layoutInfo.visibleItemsInfo.none { it.key.toString().startsWith("session-child-0-") })
        assertEquals(0, p.projectInfo().offset)
        p.snapshot("pinned-session-collapsed")
    }
}
