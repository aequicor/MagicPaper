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
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class UnifiedSessionFeedRenderTest {
    @Test fun hoistedScrollSurvivesSelectedSessionCompositionReplacement() {
        val state = LazyListState(firstVisibleItemIndex = 8)
        val selected = mutableStateOf("child-5")
        ImageComposeScene(320, 520) {
            PaperTheme {
                key(selected.value) {
                    UnifiedSessionFeed(sidebarPreviewGroups(), selected.value, true, emptySet(), { true }, {}, {},
                        { id, _ -> selected.value = id }, {}, {}, {}, state = state)
                }
            }
        }.use { scene ->
            scene.settle()
            val before = state.firstVisibleItemIndex
            assertTrue(before > 0)
            scene.nodes().single { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    node.children.any { child -> child.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Список сессий и навигация" } == true }
            }.config[SemanticsActions.OnClick].action!!.invoke()
            scene.settle()
            assertEquals("parent", selected.value)
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

    @Test fun restoredScrollPinsThreeHeadersAndTheirControlsStillWork() {
        val state = LazyListState(firstVisibleItemIndex = 8)
        val collapsed = mutableStateOf(emptySet<String>())
        var selected: Pair<String, Boolean>? = null
        ImageComposeScene(320, 520) {
            PaperTheme {
                UnifiedSessionFeed(sidebarPreviewGroups(), "child-5", true, emptySet(),
                    expanded = { it.id !in collapsed.value }, onToggleGroup = {},
                    onToggleSession = { collapsed.value = collapsed.value + it.id },
                    onSelect = { id, coding -> selected = id to coding }, onArchive = {}, onDelete = {},
                    onAddSession = {}, state = state)
            }
        }.use { scene ->
            scene.settle()
            val project = scene.nodes().filter {
                it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == "MagicPaper"
            }.minBy { it.boundsInRoot.top }
            val task = scene.text("Обновить рабочее пространство")
            val parent = scene.text("Список сессий и навигация")
            assertTrue(project.boundsInRoot.top >= 0f)
            assertTrue(task.boundsInRoot.top >= project.boundsInRoot.bottom)
            assertTrue(parent.boundsInRoot.top >= task.boundsInRoot.bottom)
            scene.save("three-levels")
            val parentControl = scene.nodes().single { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    node.children.any { child -> child.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Список сессий и навигация" } == true }
            }
            assertTrue(parentControl.config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals("parent" to true, selected)
            scene.clickDescription("Свернуть: Список сессий и навигация")
            scene.settle()
            assertEquals(setOf("parent"), collapsed.value)
            assertTrue(scene.text("Список сессий и навигация").boundsInRoot.top >= 0f,
                "Collapsing a pinned branch must retain the clicked parent in view")
            assertTrue(scene.nodes().none { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.startsWith("Этап ") } == true })
            scene.save("collapsed-parent")
        }
    }

    @Test fun chatReleasesPreviousProjectHeadersAndKeepsSessionGeometry() {
        val groups = sidebarPreviewGroups()
        val rows = sidebarFeedRows(groups, emptySet()) { true }
        val index = rows.indexOfFirst { it.session?.id == "chat" }
        val state = LazyListState(firstVisibleItemIndex = index)
        ImageComposeScene(320, 520) {
            PaperTheme {
                UnifiedSessionFeed(groups, "chat", false, emptySet(), { true }, {}, {}, { _, _ -> }, {}, {}, {}, state = state)
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
                val disclosure = scene.description("Свернуть: Список сессий и навигация")
                assertTrue(title.boundsInRoot.width > 20f)
                assertTrue(title.boundsInRoot.right <= disclosure.boundsInRoot.left)
                assertTrue(disclosure.boundsInRoot.right <= width)
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
                UnifiedSessionFeed(groups, "child-5", true, emptySet(), { true }, {}, {},
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
            repeat(4) {
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(160f, 30f), scrollDelta = Offset(0f, 4f),
                    type = PointerType.Mouse)
                scene.settle()
            }
            val pinnedTop = scene.text("Этап 6: проверка интерфейса").boundsInRoot.top
            repeat(1) {
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(160f, 30f), scrollDelta = Offset(0f, 4f),
                    type = PointerType.Mouse)
                scene.settle()
            }
            assertEquals(
                pinnedTop,
                scene.text("Этап 6: проверка интерфейса").boundsInRoot.top,
                absoluteTolerance = 0.5f,
                message = "A sticky session must retain its top position while the list scrolls",
            )
            repeat(3) {
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(160f, 30f), scrollDelta = Offset(0f, 4f),
                    type = PointerType.Mouse)
                scene.settle()
            }
            assertTrue(state.firstVisibleItemIndex > before, "Wheel events over pinned rows must reach the lazy list")
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
