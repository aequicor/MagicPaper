package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.aequicor.magicpaper.designsystem.PaperSurface
import io.aequicor.magicpaper.designsystem.PaperSurfaceKind
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingSession
import io.aequicor.magicpaper.domain.SessionKind
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.CodingUi
import java.awt.EventQueue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Actual task disclosure, selection and sticky layout; no provider or project filesystem. */
@OptIn(ExperimentalComposeUiApi::class)
class ProjectsPanelTaskGroupTest {
    private companion object {
        fun <T> onUi(block: () -> T): T {
            if (EventQueue.isDispatchThread()) return block()
            var result: Result<T>? = null
            EventQueue.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
    }

    private class Panel(
        val width: Int = 390,
        height: Int = 900,
        fontScale: Float = 1f,
        val childCount: Int = 1,
        val title: String = "Новая сессия",
    ) : AutoCloseable {
        val a = CodingProject("a", "MagicPaper", "/fixture/a", 0)
        val b = CodingProject("b", "Другой проект", "/fixture/b", 0)
        val ui = mutableStateOf(CodingUi(
            projects = listOf(a, b), current = a, currentSessionId = "root-0",
            sessions = buildList {
                repeat(2) { task ->
                    fun session(id: String, name: String, parent: String? = null, kind: SessionKind = SessionKind.SESSION) =
                        CodingSessionUi(CodingSession(id, "a", name, 0, parentSessionId = parent,
                            organismId = "organism-$task", sessionKind = kind))
                    add(session("root-$task", title, kind = SessionKind.ZYGOTE))
                    if (task == 0) {
                        repeat(childCount) { child ->
                            add(session("child-$child", "Этап $child", parent = "root-0"))
                            if (child == 0) add(session("grandchild", "Проверка этапа", parent = "child-0"))
                        }
                    }
                    add(session("immunity-$task", "Иммунитет", kind = SessionKind.IMMUNITY))
                }
                add(CodingSessionUi(CodingSession("other", "b", "Другая сессия", 0)))
            },
        ))
        val list = LazyListState()
        val selections = mutableListOf<String>()
        var projectSelections = 0
        private var frame = 0L
        private val scene = onUi { ImageComposeScene(width, height, density = Density(1f, fontScale)) {
            PaperTheme {
                PaperSurface(Modifier.fillMaxSize(), PaperSurfaceKind.CANVAS) {
                    ProjectsPanel(ui.value, {}, { id ->
                        projectSelections++
                        ui.value = ui.value.copy(current = ui.value.projects.single { it.id == id })
                    }, {}, { id ->
                        selections += id
                        ui.value = ui.value.copy(currentSessionId = id)
                    }, {}, {}, {}, listState = list)
                }
            }
        } }

        init { render() }

        fun render() = repeat(12) {
            onUi { scene.render(++frame * 32_000_000L).close() }
            Thread.sleep(10)
        }

        fun update(transform: (CodingUi) -> CodingUi) {
            onUi { ui.value = transform(ui.value) }
            render()
        }

        fun keys() = onUi { list.layoutInfo.visibleItemsInfo.map { it.key }.toSet() }

        private fun nodes(): List<SemanticsNode> = onUi {
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) +
                if (node.config.isClearingSemantics) emptyList() else node.children.flatMap(::walk)
            scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                .filter { it.boundsInRoot.height > 0f }
        }

        fun taskHeader(task: Int): SemanticsNode {
            val row = onUi { list.layoutInfo.visibleItemsInfo.single { it.key == "task-organism-$task" } }
            return nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Задача: $title") == true &&
                    it.boundsInRoot.center.y in row.offset.toFloat()..(row.offset + row.size).toFloat()
            }
        }

        fun pinnedTaskHeader(): SemanticsNode {
            val project = onUi { list.layoutInfo.visibleItemsInfo.single { it.key == "project-a" } }
            val top = (project.offset + project.size).coerceAtLeast(0)
            return nodes().filter {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Задача: $title") == true &&
                    it.boundsInRoot.top >= top
            }.minBy { it.boundsInRoot.top }
        }

        fun taskHasStatus(task: Int, label: String): Boolean {
            val bounds = taskHeader(task).boundsInRoot
            return nodes().any {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true &&
                    bounds.contains(it.boundsInRoot.center)
            }
        }

        fun clickItem(key: String) {
            val row = onUi { list.layoutInfo.visibleItemsInfo.single { it.key == key } }
            click(Offset(width / 2f, row.offset + row.size / 2f))
        }

        fun click(node: SemanticsNode) = click(node.boundsInRoot.center)

        private fun click(point: Offset) {
            onUi {
                scene.sendPointerEvent(PointerEventType.Press, point)
                scene.sendPointerEvent(PointerEventType.Release, point)
            }
            render()
        }

        fun toggleChildren(id: String, label: String) {
            val row = onUi { list.layoutInfo.visibleItemsInfo.single { it.key == "session-$id" } }
            onUi {
                scene.sendPointerEvent(PointerEventType.Move, Offset(width / 2f, row.offset + row.size / 2f),
                    type = PointerType.Mouse)
            }
            render()
            click(nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true &&
                    it.boundsInRoot.center.y in row.offset.toFloat()..(row.offset + row.size).toFloat()
            })
        }

        fun scrollTo(index: Int, offset: Int = 0) {
            onUi { list.requestScrollToItem(index, offset) }
            render()
        }

        fun wheel(node: SemanticsNode) {
            onUi { scene.sendPointerEvent(PointerEventType.Scroll, node.boundsInRoot.center,
                scrollDelta = Offset(0f, 3f)) }
            render()
        }

        fun snapshot(name: String) {
            val bytes = onUi { scene.render(++frame * 32_000_000L).use {
                it.encodeToData()!!.use { data -> data.bytes }
            } }
            File("build/reports/project-task-groups").apply { mkdirs() }.resolve("$name.png").writeBytes(bytes)
        }

        override fun close() = onUi { scene.close() }
    }

    @Test fun sameNamedTasksKeepIndependentDisclosureAndRouteEachSession() = Panel().use { p ->
        assertTrue(setOf("task-organism-0", "task-organism-1", "session-grandchild", "session-immunity-0")
            .all { it in p.keys() })
        p.click(p.taskHeader(0))
        assertFalse("session-root-0" in p.keys())
        assertFalse("session-immunity-0" in p.keys())
        assertTrue("session-root-1" in p.keys(), "Equal task names must not share disclosure state")
        assertEquals("Раскрыть задачу", p.taskHeader(0).config.getOrNull(SemanticsActions.OnClick)?.label)
        assertEquals("Свёрнута", p.taskHeader(0).config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals("root-0", p.ui.value.currentSessionId)
        assertTrue(p.selections.isEmpty(), "Task disclosure must not select or reload a session")

        p.update { ui -> ui.copy(sessions = ui.sessions.map { it.copy(running = it.session.id == "immunity-0") }) }
        assertFalse("session-immunity-0" in p.keys(), "Status updates must preserve a collapsed task")
        assertTrue(p.taskHasStatus(0, "работает"), "A collapsed task must still show its hidden member's status")
        p.clickItem("project-b")
        p.clickItem("project-a")
        assertFalse("session-root-0" in p.keys(), "Returning to the project must preserve task disclosure")
        p.snapshot("collapsed-active-task")

        p.click(p.taskHeader(0))
        p.toggleChildren("root-0", "Свернуть этапы")
        assertFalse("session-child-0" in p.keys())
        assertTrue("session-immunity-0" in p.keys(), "Immunity must be independent of the zygote subtree")
        p.clickItem("session-immunity-0")
        p.clickItem("session-root-0")
        assertEquals(listOf("immunity-0", "root-0"), p.selections)
        assertFalse("session-child-0" in p.keys(), "Selecting the zygote must preserve its collapsed subtree")
        p.toggleChildren("root-0", "Раскрыть этапы")
        p.clickItem("session-grandchild")
        assertEquals("grandchild", p.ui.value.currentSessionId)
        assertEquals(2, p.projectSelections, "Task and subtree disclosure must not reload the project")
        p.snapshot("same-named-tasks-expanded")
    }

    @Test fun pinnedTaskHeaderKeepsItsProjectAndCollapseAnchor() = Panel(height = 500, childCount = 18).use { p ->
        p.scrollTo(10, 11)
        val project = p.list.layoutInfo.visibleItemsInfo.single { it.key == "project-a" }
        assertEquals(0, project.offset)
        val header = p.pinnedTaskHeader()
        assertTrue(header.boundsInRoot.top >= project.size,
            "The task header must remain below its project while descendants scroll")
        val before = p.list.firstVisibleItemIndex to p.list.firstVisibleItemScrollOffset
        p.wheel(header)
        assertFalse(before == (p.list.firstVisibleItemIndex to p.list.firstVisibleItemScrollOffset),
            "Scrolling over the pinned task must continue scrolling the tree")
        p.snapshot("pinned-task-over-descendants")
        p.click(p.pinnedTaskHeader())
        assertFalse(p.keys().any { it.toString().startsWith("session-child-") })
        assertFalse("session-immunity-0" in p.keys())
        assertTrue("task-organism-0" in p.keys(), "Collapsing scrolled descendants must keep their task in view")
        assertEquals(0, p.list.layoutInfo.visibleItemsInfo.single { it.key == "project-a" }.offset)
        assertEquals("root-0", p.ui.value.currentSessionId)
        assertTrue(p.selections.isEmpty())
        p.snapshot("pinned-task-collapsed")
        p.click(p.taskHeader(0))
        assertTrue("session-root-0" in p.keys())
    }

    @Test fun taskHeaderRemainsUsableAtNarrowWidthAndDoubleTextScale() {
        for ((width, scale) in listOf(390 to 1f, 200 to 1f, 200 to 2f)) {
            Panel(width = width, height = 1100, fontScale = scale,
                title = "Проверить восстановление задачи после перезапуска").use { p ->
                val header = p.taskHeader(0)
                assertTrue(header.boundsInRoot.left >= 0f && header.boundsInRoot.right <= width)
                assertTrue(header.boundsInRoot.width > 0f && header.boundsInRoot.height > 0f)
                assertEquals("Свернуть задачу", header.config.getOrNull(SemanticsActions.OnClick)?.label)
                p.snapshot("task-groups-${width}dp-${(scale * 100).toInt()}percent")
                p.click(header)
                assertFalse("session-root-0" in p.keys())
                p.click(p.taskHeader(0))
                assertTrue("session-root-0" in p.keys())
                assertTrue(p.selections.isEmpty())
            }
        }
    }
}
