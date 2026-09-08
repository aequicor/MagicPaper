package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingSession
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.CodingUi
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.io.File
import kotlin.test.*

/** Real panel/pointer events; controlled UI state, no process, provider or project filesystem. */
class ProjectsPanelCollapseTest {
    private class Panel : AutoCloseable {
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
        private var frame = 0L
        private val scene = ImageComposeScene(390, 900) {
            MagicPaperTheme {
                ProjectsPanel(ui.value, {}, { id ->
                    projectClicks++
                    // Same selection contract as openCodingProject: selection is not cleared
                    // when the already-current project is opened again.
                    ui.value = ui.value.copy(current = ui.value.projects.single { it.id == id })
                }, {}, { id -> sessionClicks++; ui.value = ui.value.copy(currentSessionId = id) }, {}, {}, {}, listState = list)
            }
        }
        init { render() }
        fun render() { repeat(12) { scene.render(++frame * 32_000_000L).close(); Thread.sleep(10) } }
        fun keys() = list.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
        fun click(key: String, x: Float = 100f) {
            val item = list.layoutInfo.visibleItemsInfo.single { it.key == key }
            // Panel heading: titleMedium line 24 + vertical padding 24 + divider 1.
            val point = Offset(x, 49f + item.offset + item.size / 2f)
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
        assertEquals(1, p.sessionClicks, "Clicking the row selects the parent and toggles its stages")
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
        p.click("session-parent", 335f)
        assertTrue("session-child" in p.keys())
        assertEquals("parent", p.ui.value.currentSessionId, "The arrow uses the same action as the whole row")
        p.click("session-child")
        assertEquals("child", p.ui.value.currentSessionId)
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
}
