package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.aequicor.magicpaper.designsystem.PaperTheme
import java.io.File
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class UnifiedSessionFeedRenderTest {
    @Test fun selectingSessionRetainsViewportWhenStatusReordersItToTop() {
        fun item(index: Int) = UnifiedSidebarItem(
            id = "session-$index",
            displayName = "Session $index",
            sortTime = (20 - index).toLong(),
            isCoding = true,
            projectName = "Project",
            projectId = "project",
        )
        val initialItems = List(20, ::item)
        val groups = mutableStateOf(groupUnifiedSidebarItems(initialItems))
        val state = LazyListState(firstVisibleItemIndex = 8)
        var selected: String? = null
        ImageComposeScene(320, 240) {
            PaperTheme {
                UnifiedSessionFeed(groups.value, selected, true, emptySet(), {}, { id, _ ->
                    selected = id
                    val moved = initialItems.first { it.id == id }.copy(sortTime = 100L)
                    groups.value = groupUnifiedSidebarItems(listOf(moved) + initialItems.filterNot { it.id == id })
                }, {}, {}, {}, state = state)
            }
        }.use { scene ->
            scene.settle()
            val before = state.firstVisibleItemIndex
            assertTrue(before > 0)
            val target = sidebarFeedRows(groups.value, emptySet())[before].session!!
            val visibleSession = scene.nodes().first { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    node.children.any { child -> child.config.getOrNull(SemanticsProperties.Text)?.any { it.text == target.displayName } == true }
            }
            assertTrue(visibleSession.config[SemanticsActions.OnClick].action!!.invoke())
            scene.settle()
            assertEquals(target.id, selected)
            assertEquals(before, state.firstVisibleItemIndex)
        }
    }

    @Test fun hoistedScrollSurvivesSelectedSessionCompositionReplacement() {
        val state = LazyListState(firstVisibleItemIndex = 8)
        val selected = mutableStateOf("child-5")
        ImageComposeScene(320, 520) {
            PaperTheme {
                key(selected.value) {
                    UnifiedSessionFeed(sidebarPreviewGroups(), selected.value, true, emptySet(), {},
                        { id, _ -> selected.value = id }, {}, {}, {}, state = state)
                }
            }
        }.use { scene ->
            scene.settle()
            val before = state.firstVisibleItemIndex
            assertTrue(before > 0)
            val target = sidebarFeedRows(sidebarPreviewGroups(), emptySet())[before].session!!
            scene.nodes().single { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    node.children.any { child -> child.config.getOrNull(SemanticsProperties.Text)?.any { it.text == target.displayName } == true }
            }.config[SemanticsActions.OnClick].action!!.invoke()
            scene.settle()
            assertEquals(target.id, selected.value)
            assertEquals(before, state.firstVisibleItemIndex)
        }
    }

    @Test fun sessionProjectionKeepsParentsAndPromotesChildrenOfArchivedNodes() {
        fun session(id: String, parent: String? = null, archived: Boolean = false) =
            io.aequicor.magicpaper.ui.CodingSessionUi(io.aequicor.magicpaper.domain.CodingSession(
                id, "project", id, 10L, parentSessionId = parent, archived = archived))
        val coding = io.aequicor.magicpaper.ui.CodingUi(sessions = listOf(
            session("root"), session("parent", "root"), session("archived", "parent", true), session("leaf", "archived")))
        var items = emptyList<UnifiedSidebarItem>()
        ImageComposeScene(1, 1) {
            items = rememberUnifiedItems(emptyList(), coding, null, true, remember { SessionRecencyTracker { 20L } })
        }.use { it.settle() }
        assertEquals("root", items.single().id)
        assertEquals("parent", items.single().children.single().id)
        assertEquals("leaf", items.single().children.single().children.single().id)
    }

    @Test fun restoredScrollPinsOneLevelSessionHeaders() {
        val state = LazyListState(firstVisibleItemIndex = 8)
        ImageComposeScene(320, 520) {
            PaperTheme {
                UnifiedSessionFeed(sidebarPreviewGroups(), "child-5", true, emptySet(),
                    onToggleGroup = {},
                    onSelect = { _, _ -> }, onArchive = {}, onDelete = {},
                    onAddSession = {}, state = state)
            }
        }.use { scene ->
            scene.settle()
            val project = scene.nodes().filter {
                it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == "MagicPaper"
            }.minBy { it.boundsInRoot.top }
            val task = scene.text("Обновить рабочее пространство")
            val selectedSession = scene.text("Этап 6: проверка интерфейса")
            assertTrue(project.boundsInRoot.top >= 0f)
            assertTrue(task.boundsInRoot.top >= project.boundsInRoot.bottom)
            assertTrue(selectedSession.boundsInRoot.top >= task.boundsInRoot.bottom)
            assertTrue(abs(task.boundsInRoot.left - selectedSession.boundsInRoot.left) < 1f,
                "Sticky session rows must remain on the same visual level")
            scene.save("one-level-sticky-sessions")
            assertTrue(scene.nodes().none {
                it.config.getOrNull(SemanticsProperties.ContentDescription)
                    ?.contains("Свернуть: Список сессий и навигация") == true
            })
        }
    }

    @Test fun chatReleasesPreviousProjectHeadersAndKeepsSessionGeometry() {
        val groups = sidebarPreviewGroups()
        val rows = sidebarFeedRows(groups, emptySet())
        val index = rows.indexOfFirst { it.session?.id == "chat" }
        val state = LazyListState(firstVisibleItemIndex = index)
        ImageComposeScene(320, 520) {
            PaperTheme {
                UnifiedSessionFeed(groups, "chat", false, emptySet(), {}, { _, _ -> }, {}, {}, {}, state = state)
            }
        }.use { scene ->
            scene.settle()
            val chat = scene.text("Обсуждение дизайна")
            val project = scene.text("MagicPaper")
            assertTrue(chat.boundsInRoot.top < project.boundsInRoot.top)
            assertTrue(scene.nodes().none { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Обновить рабочее пространство" } == true })
            scene.save("chat-between-projects")
        }
    }

    @Test fun previewRendersAtNormalAndNarrowLargeTextSizes() {
        for ((width, scale) in listOf(320 to 1f, 240 to 2f)) {
            ImageComposeScene(width, 720) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) { UnifiedSessionFeedPreview() }
            }.use { scene ->
                scene.settle()
                val title = scene.text("Список сессий и навигация")
                assertTrue(title.boundsInRoot.width > 20f)
                assertTrue(title.boundsInRoot.right <= width)
                assertTrue(scene.nodes().none {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)
                        ?.contains("Свернуть: Список сессий и навигация") == true
                })
                scene.save("preview-$width-${(scale * 100).toInt()}")
            }
        }
    }

    @Test fun wheelOverPinnedRowsContinuesScrollingAndHoverRevealsActions() {
        val state = LazyListState(firstVisibleItemIndex = 8)
        var archived: String? = null
        fun markUnread(item: UnifiedSidebarItem): UnifiedSidebarItem = item.copy(
            unread = item.id == "child-5",
            children = item.children.map(::markUnread),
        )
        val groups = sidebarPreviewGroups().map { group -> group.copy(items = group.items.map(::markUnread)) }
        ImageComposeScene(320, 420) {
            PaperTheme {
                UnifiedSessionFeed(groups, "child-5", true, emptySet(), {},
                    { _, _ -> }, { archived = it.id }, {}, {}, state = state)
            }
        }.use { scene ->
            scene.settle()
            val before = state.firstVisibleItemIndex
            val pinned = scene.text("Этап 6: проверка интерфейса")
            scene.sendPointerEvent(PointerEventType.Exit, Offset(-1f, -1f), type = PointerType.Mouse)
            scene.settle()
            scene.sendPointerEvent(PointerEventType.Move, pinned.boundsInRoot.center, type = PointerType.Mouse)
            scene.settle()
            assertTrue(scene.nodes().any {
                it.boundsInRoot.width > 0 &&
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Действия") == true
            })
            val archive = scene.nodes().filter {
                it.boundsInRoot.width > 0 &&
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Архивировать сессию") == true
            }.minBy { node ->
                val distance = node.boundsInRoot.center.y - pinned.boundsInRoot.center.y
                distance * distance
            }
            scene.sendPointerEvent(PointerEventType.Move, archive.boundsInRoot.center, type = PointerType.Mouse)
            scene.settle()
            assertTrue(scene.nodes().any {
                it.boundsInRoot.width > 0 &&
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Архивировать сессию") == true
            },
                "Moving from the row onto its action must keep hover actions visible")
            assertTrue(scene.nodes().none {
                it.boundsInRoot.width > 0 &&
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Непрочитанное сообщение") == true
            }, "A coding session must not duplicate its status indicator in hover actions")
            scene.save("hover-actions")
            assertTrue(archive.config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals("child-5", archived)
            repeat(8) {
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(160f, 30f), scrollDelta = Offset(0f, 4f),
                    type = PointerType.Mouse)
                scene.settle()
            }
            assertTrue(state.firstVisibleItemIndex > before, "Wheel events over pinned rows must reach the lazy list")
        }
    }

    @Test fun selectedWorkingAndWaitingSessionsStackUnderTheirProject() {
        fun session(id: String, title: String, status: io.aequicor.magicpaper.domain.CodingSessionStatus) =
            UnifiedSidebarItem(id, title, 100, true, projectId = "p", projectName = "Project", codingStatus = status)
        val items = listOf(
            session("selected", "Выбранный чат", io.aequicor.magicpaper.domain.CodingSessionStatus.IDLE),
            session("working", "Рабочий чат", io.aequicor.magicpaper.domain.CodingSessionStatus.WORKING),
            session("waiting", "Чат ждёт ответа", io.aequicor.magicpaper.domain.CodingSessionStatus.WAITING),
        ) + List(12) {
            session("ordinary-$it", "Обычный чат $it", io.aequicor.magicpaper.domain.CodingSessionStatus.IDLE)
        }
        val groups = listOf(UnifiedSidebarGroup("project:p", "p", "Project", items))
        val state = LazyListState(firstVisibleItemIndex = 8)
        ImageComposeScene(320, 420) {
            PaperTheme {
                UnifiedSessionFeed(groups, "selected", true, emptySet(), {},
                    { _, _ -> }, {}, {}, {}, state = state)
            }
        }.use { scene ->
            scene.settle()
            val project = scene.text("Project")
            val selected = scene.text("Выбранный чат")
            val working = scene.text("Рабочий чат")
            val waiting = scene.text("Чат ждёт ответа")

            assertTrue(project.boundsInRoot.top >= 0f)
            assertTrue(selected.boundsInRoot.top >= project.boundsInRoot.bottom)
            assertTrue(working.boundsInRoot.top >= selected.boundsInRoot.bottom)
            assertTrue(waiting.boundsInRoot.top >= working.boundsInRoot.bottom)
            assertTrue(abs(selected.boundsInRoot.left - working.boundsInRoot.left) < 1f)
            assertTrue(abs(working.boundsInRoot.left - waiting.boundsInRoot.left) < 1f)
            scene.save("flat-status-headers")
        }
    }

    private fun ImageComposeScene.settle() {
        repeat(15) { Snapshot.sendApplyNotifications(); render((it + 1) * 16_000_000L).close() }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.text(value: String) = nodes().single {
        it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == value
    }
    private fun ImageComposeScene.description(value: String) = nodes().single {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(value) == true
    }
    private fun ImageComposeScene.clickDescription(value: String) {
        assertTrue(description(value).config[SemanticsActions.OnClick].action!!.invoke())
    }
    private fun ImageComposeScene.save(name: String) {
        val file = File("build/reports/session-list/$name.png")
        file.parentFile.mkdirs()
        render(256_000_000L).use { image -> image.encodeToData()!!.use { file.writeBytes(it.bytes) } }
    }
}
