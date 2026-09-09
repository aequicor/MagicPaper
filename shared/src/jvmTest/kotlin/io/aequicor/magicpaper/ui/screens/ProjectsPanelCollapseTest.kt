package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingSession
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.CodingUi
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.io.File
import kotlin.test.*

/** Real panel/pointer events; controlled UI state, no process, provider or project filesystem. */
@OptIn(ExperimentalComposeUiApi::class)
class ProjectsPanelCollapseTest {
    private class Panel(private val width: Int = 390) : AutoCloseable {
        val a = CodingProject("a", "Project A", "/fixture/a", 0)
        val b = CodingProject("b", "Project B", "/fixture/b", 0)
        val ui = mutableStateOf(CodingUi(projects = listOf(a, b), current = a, currentSessionId = "parent", sessions = listOf(
            CodingSessionUi(CodingSession("parent", "a", "Plan", 0, planningMode = true)),
            CodingSessionUi(CodingSession("child", "a", "Stage", 0, parentSessionId = "parent")),
            CodingSessionUi(CodingSession("ordinary", "a", "Ordinary", 0)),
            CodingSessionUi(CodingSession("other", "b", "Other", 0)),
        )))
        val list = LazyListState()
        var projectClicks = 0
        var sessionClicks = 0
        var addSessionClicks = 0
        private var frame = 0L
        private val scene = ImageComposeScene(width, 900) {
            MagicPaperTheme {
                ProjectsPanel(ui.value, {}, { id ->
                    projectClicks++
                    // Same selection contract as openCodingProject: selection is not cleared
                    // when the already-current project is opened again.
                    ui.value = ui.value.copy(current = ui.value.projects.single { it.id == id })
                }, {}, { id -> sessionClicks++; ui.value = ui.value.copy(currentSessionId = id) },
                    { addSessionClicks++ }, {}, {}, listState = list)
            }
        }
        init { render() }
        fun render() { repeat(12) { scene.render(++frame * 32_000_000L).close(); Thread.sleep(10) } }
        fun keys() = list.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
        fun click(key: String, x: Float = 100f) {
            val item = list.layoutInfo.visibleItemsInfo.single { it.key == key }
            // The list starts at the scene origin; there is no panel heading above it.
            click(Offset(x, item.offset + item.size / 2f))
        }
        fun clickDisclosure(key: String, label: String) {
            val item = list.layoutInfo.visibleItemsInfo.single { it.key == key }
            scene.sendPointerEvent(PointerEventType.Move, Offset(100f, item.offset + item.size / 2f),
                type = PointerType.Mouse)
            render()
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + if (node.config.isClearingSemantics) emptyList() else node.children.flatMap(::walk)
            val arrow = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
            }
            click(arrow.boundsInRoot.center)
        }
        private fun nodes(): List<SemanticsNode> {
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + if (node.config.isClearingSemantics) emptyList() else node.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
        }
        fun hover(key: String) {
            val item = list.layoutInfo.visibleItemsInfo.single { it.key == key }
            scene.sendPointerEvent(PointerEventType.Move, Offset(100f, item.offset + item.size / 2f), type = PointerType.Mouse)
            render()
        }
        fun newSessionButton(): SemanticsNode = nodes().single {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Новая сессия") == true
        }
        fun hasText(value: String): Boolean = nodes().any {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
        }
        fun text(value: String): SemanticsNode = nodes().single {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
        }
        fun actionsMenu(): SemanticsNode = nodes().single {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Действия") == true
        }
        fun click(node: SemanticsNode) = click(node.boundsInRoot.center)
        private fun click(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, point)
            scene.sendPointerEvent(PointerEventType.Release, point)
            render()
        }
        fun snapshot(name: String) {
            val directory = File("build/reports/project-collapse").apply { mkdirs() }
            File(directory, "$name.png").writeBytes(scene.render(++frame * 32_000_000L).use {
                it.encodeToData()!!.use { data -> data.bytes }
            })
        }
        override fun close() = scene.close()
    }

    @Test fun childCollapseSurvivesUpdatesSelectionAndProjectRoundTrip() = Panel().use { p ->
        assertTrue("session-child" in p.keys())
        p.click("session-parent")
        assertFalse("session-child" in p.keys(), "Child disclosure must hide the stage")
        assertEquals(0, p.sessionClicks, "An already-selected parent only toggles its stages")
        p.ui.value = p.ui.value.copy(sessions = p.ui.value.sessions.map { it.copy(running = it.session.id == "child") })
        p.render()
        assertFalse("session-child" in p.keys(), "Live status must not reopen the collapsed group")
        p.click("session-ordinary")
        assertEquals("ordinary", p.ui.value.currentSessionId)
        p.click("project-b")
        assertTrue("session-other" in p.keys())
        assertFalse("session-parent" in p.keys())
        p.click("project-a")
        assertFalse("session-child" in p.keys(), "Returning to the project preserves collapsed children")
        p.click("session-parent")
        assertEquals("parent", p.ui.value.currentSessionId)
        assertFalse("session-child" in p.keys(), "Selecting a parent must preserve its collapsed state")
        p.click("session-parent")
        assertTrue("session-child" in p.keys())
        p.click("session-child")
        assertEquals("child", p.ui.value.currentSessionId)
        p.clickDisclosure("session-parent", "Свернуть этапы")
        assertFalse("session-child" in p.keys())
        assertEquals("child", p.ui.value.currentSessionId, "The arrow must not select its parent")
        p.clickDisclosure("session-parent", "Раскрыть этапы")
        assertTrue("session-child" in p.keys())
        assertEquals("child", p.ui.value.currentSessionId)
        p.click("session-parent")
        assertTrue("session-child" in p.keys(), "Selecting an expanded parent must keep its stages visible")
        p.snapshot("children-reopened")
    }

    @Test fun selectedProjectHeaderCollapsesAndReopensItsSessionList() = Panel().use { p ->
        assertTrue("session-parent" in p.keys())
        p.snapshot("project-before-click")
        p.click("project-a")
        p.snapshot("project-after-click")
        assertEquals(0, p.projectClicks, "Disclosure must not reload the selected project")
        assertFalse("session-parent" in p.keys(), "Repeated click on current project must collapse its session list")
        assertEquals("parent", p.ui.value.currentSessionId, "Collapsing must preserve active session")
        p.ui.value = p.ui.value.copy(sessions = p.ui.value.sessions.map { it.copy(running = it.session.id == "child") })
        p.render()
        assertFalse("session-parent" in p.keys(), "Status update must not reopen the project")
        p.click("project-a")
        assertTrue("session-parent" in p.keys())
        assertTrue("session-child" in p.keys())
        assertEquals("parent", p.ui.value.currentSessionId)
        assertEquals(0, p.projectClicks)
        p.snapshot("project-reopened")
        p.click("project-a")
        p.click("project-b")
        assertTrue("session-other" in p.keys())
        p.click("project-a")
        assertTrue("session-parent" in p.keys(), "Selecting another project opens its list")
        assertTrue("session-child" in p.keys())
    }

    @Test fun hoveringKeepsRowAndTitleBoundsStable() = Panel().use { p ->
        val rows = p.list.layoutInfo.visibleItemsInfo.associate { it.key to (it.offset to it.size) }
        val titles = listOf("Project A", "Plan", "Stage", "Ordinary").associateWith { p.text(it).boundsInRoot }
        for (key in listOf("project-a", "session-parent", "session-child", "session-ordinary", "project-b")) {
            p.hover(key)
            assertEquals(rows, p.list.layoutInfo.visibleItemsInfo.associate { it.key to (it.offset to it.size) })
            titles.forEach { (title, bounds) -> assertEquals(bounds, p.text(title).boundsInRoot, title) }
        }
    }

    @Test fun newSessionButtonUsesOnlyItsCallback() = Panel().use { p ->
        p.hover("project-a")
        val button = p.newSessionButton()
        assertNotNull(button.config.getOrNull(SemanticsActions.OnClick), "New-session control must be clickable by semantics")
        assertFalse(p.hasText("Удалить проект"), "Project actions menu must be closed before the click")
        p.snapshot("new-session-action")

        p.click(button)

        assertEquals(1, p.addSessionClicks, "New-session callback must run exactly once")
        assertEquals(0, p.projectClicks, "New-session click must not select the project")
        assertEquals(0, p.sessionClicks, "New-session click must not select a session")
        assertTrue("session-parent" in p.keys(), "New-session click must not collapse the current project")
        assertTrue("session-child" in p.keys(), "New-session click must preserve the expanded session group")
        assertFalse(p.hasText("Удалить проект"), "New-session click must not open the project actions menu")
    }

    @Test fun newSessionActionKeepsHeaderControlsSeparateAtMinimumSidebarWidth() = Panel(width = 200).use { p ->
        p.hover("project-a")
        val title = p.text("Project A").boundsInRoot
        val button = p.newSessionButton().boundsInRoot
        val menu = p.actionsMenu().boundsInRoot

        assertTrue(title.width > 0f, "Project title must remain visible at the minimum sidebar width")
        assertTrue(title.right <= button.left, "New-session button must not cover the project title")
        assertTrue(button.right <= menu.left, "New-session button must stay left of the actions menu")
        assertTrue(menu.right <= 200f, "Actions menu must remain inside the sidebar")
        p.snapshot("new-session-minimum-width")
    }
}
