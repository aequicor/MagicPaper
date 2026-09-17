package io.aequicor.magicpaper.designsystem

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.use
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class PaperResearchSourceControlsTest {
    @Test fun entireSourceAndGroupHoverTogetherWhileDomainClickOnlyOpensTheWebsite() {
        for (scale in listOf(1f, 2f)) {
            val width = if (scale == 1f) 300 else 340
            var opened = 0
            val scene = onPaperUi { ImageComposeScene(width, 400) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, scale)) {
                    PaperResearchSourceControlsPreview { opened++ }
                }
            } }
            var frame = 0L
            fun settle() = onPaperUi { repeat(8) { scene.render(++frame * 32_000_000L).close() } }
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            fun nodes() = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            fun action(label: String) = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label) }
            fun bounds(tag: String) = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }.boundsInRoot
            fun capture(name: String) = onPaperUi {
                val bytes = scene.render(++frame * 32_000_000L).use { image -> image.encodeToData()!!.use { it.bytes } }
                File("build/reports/research-source-controls/$name-$scale.png").apply { parentFile.mkdirs(); writeBytes(bytes) }
                ImageIO.read(ByteArrayInputStream(bytes))
            }
            fun move(point: Offset) { onPaperUi { scene.sendPointerEvent(PointerEventType.Move, point, type = PointerType.Mouse) }; settle() }
            try {
                settle()
                val row = onPaperUi { bounds("source-row") }
                val group = onPaperUi { bounds("source-group") }
                val before = capture("full-row-idle")
                val checkboxLabel = "Использовать источник: system_design_replit.md · GitHub"
                for ((name, label) in listOf("checkbox" to checkboxLabel, "domain" to "Открыть сайт: github.com", "menu" to "Действия с источником")) {
                    move(onPaperUi { action(label).boundsInRoot.center })
                    val hovered = capture("full-row-$name")
                    for (x in listOf(3, width - 4)) {
                        assertNotEquals(before.getRGB(x, row.center.y.toInt()), hovered.getRGB(x, row.center.y.toInt()),
                            "Hover over $name covers both edges, including the checkbox and menu lanes")
                        assertNotEquals(before.getRGB(x, (row.bottom - 8).toInt()), hovered.getRGB(x, (row.bottom - 8).toInt()),
                            "The domain row belongs to the same hover surface")
                    }
                }
                move(onPaperUi { action("Открыть сайт: github.com").boundsInRoot.center })
                onPaperUi {
                    val point = action("Открыть сайт: github.com").boundsInRoot.center
                    scene.sendPointerEvent(PointerEventType.Press, point, type = PointerType.Mouse, button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, point, type = PointerType.Mouse, button = PointerButton.Primary)
                }
                settle()
                onPaperUi {
                    assertEquals(1, opened)
                    assertEquals(androidx.compose.ui.state.ToggleableState.On, action(checkboxLabel).config[SemanticsProperties.ToggleableState])
                    action("Открыть сайт: github.com").config[SemanticsActions.RequestFocus].action!!.invoke()
                    scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown))
                    scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyUp))
                    assertEquals(2, opened)
                }
                for ((name, label) in listOf("selection" to "Снять выбор со всех: Общие для чата", "disclosure" to "Свернуть: Общие для чата", "add" to "Добавить файлы")) {
                    move(onPaperUi { action(label).boundsInRoot.center })
                    val hovered = capture("full-group-$name")
                    // Sample above the checkmark so its opaque border doesn't mask the row background at 2x.
                    for (x in listOf(4, width - 5)) assertNotEquals(before.getRGB(x, (group.top + 4).toInt()), hovered.getRGB(x, (group.top + 4).toInt()),
                        "Hover over $name covers the whole group header")
                    val dividerY = (group.bottom - 1).toInt()
                    assertEquals(before.getRGB(width / 2, dividerY), hovered.getRGB(width / 2, dividerY), "Divider stays outside hover")
                    assertNotEquals(before.getRGB(width / 2, dividerY + 1), hovered.getRGB(width / 2, dividerY), "Divider separates header from list")
                }
            } finally { onPaperUi { scene.close() } }
        }
    }

    @Test fun compactErrorsShareTheDomainRowAndOpenReadingAtBothTextScales() {
        for (scale in listOf(1f, 2f)) {
            val width = if (scale == 1f) 300 else 360
            var reads = 0
            var removals = 0
            val scene = onPaperUi { ImageComposeScene(width, 600) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, scale)) {
                    PaperResearchCompactSourcesPreview(onRemove = { removals++ }) { reads++ }
                }
            } }
            try {
                onPaperUi {
                    repeat(6) { scene.render((it + 1) * 32_000_000L).close() }
                    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                    val nodes = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                    fun text(value: String) = nodes.first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == value } }
                    val domain = text("perforce.com").boundsInRoot
                    val status = text("HTTP 403").boundsInRoot
                    assertTrue(status.left >= domain.right)
                    assertEquals(domain.center.y, status.center.y, 1f)
                    assertTrue(status.right <= width)
                    assertFalse(nodes.any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.startsWith("Не используется:") } })
                    nodes.first { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Прочитать в браузере: Ошибка HTTP 403") }
                        .config[SemanticsActions.OnClick].action!!.invoke()
                    assertEquals(1, reads)
                    val removeActions = nodes.filter { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                        .any { label -> label.startsWith("Убрать источник:") } }
                    assertEquals(3, removeActions.size, "Only unreadable sources have a permanent remove action")
                    val remove = removeActions[1]
                    assertTrue(remove.boundsInRoot.left >= status.right)
                    assertEquals(status.center.y, remove.boundsInRoot.center.y, 1f)
                    assertTrue(remove.boundsInRoot.right <= width)
                    val file = File("build/reports/research-source-controls/compact-errors-$scale.png").apply { parentFile.mkdirs() }
                    scene.render(240_000_000L).use { image -> image.encodeToData()!!.use { file.writeBytes(it.bytes) } }
                    val bounds = remove.boundsInRoot
                    val pixels = ImageIO.read(file).getRGB(bounds.left.toInt(), bounds.top.toInt(),
                        bounds.width.toInt(), bounds.height.toInt(), null, 0, bounds.width.toInt())
                    assertTrue(pixels.distinct().size > 1, "Delete icon is painted without pointer hover or keyboard focus")
                    remove.config[SemanticsActions.OnClick].action!!.invoke()
                    assertEquals(1, removals)
                    assertEquals(1, reads, "Removal must not retry reading")
                }
            } finally { onPaperUi { scene.close() } }
        }
    }
    @Test fun groupSelectionShowsNonePartialAllAndEmptyAtBothTextScales() {
        for (scale in listOf(1f, 2f)) {
            val width = if (scale == 1f) 300 else 340
            val scene = onPaperUi { ImageComposeScene(width, if (scale == 1f) 240 else 460) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, scale)) {
                    PaperResearchSourceSelectionPreview()
                }
            } }
            try {
                onPaperUi {
                    repeat(6) { scene.render((it + 1) * 32_000_000L).close() }
                    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                    val nodes = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                    val checks = nodes.filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox }
                    assertEquals(listOf(androidx.compose.ui.state.ToggleableState.Off,
                        androidx.compose.ui.state.ToggleableState.Indeterminate,
                        androidx.compose.ui.state.ToggleableState.On,
                        androidx.compose.ui.state.ToggleableState.Off), checks.map { it.config[SemanticsProperties.ToggleableState] })
                    assertTrue(checks.last().config.contains(SemanticsProperties.Disabled))
                    val disclosures = nodes.filter { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                        .any { label -> label.startsWith("Свернуть:") || label.startsWith("Развернуть:") } }
                    assertEquals(4, disclosures.size)
                    checks.zip(disclosures).forEach { (check, disclosure) ->
                        assertTrue(check.boundsInRoot.right <= disclosure.boundsInRoot.left)
                        assertTrue(disclosure.boundsInRoot.right < width, "The trailing add action stays inside the pane")
                    }
                    File("build/reports/research-source-controls/group-selection-$scale.png").apply { parentFile.mkdirs() }
                        .writeBytes(scene.render(224_000_000L).use { image -> image.encodeToData()!!.use { it.bytes } })
                }
            } finally { onPaperUi { scene.close() } }
        }
    }

    @Test fun menuAppearsOnHoverAndKeyboardFocusWithoutMovingTheSourceTitle() {
        for (scale in listOf(1f, 2f)) {
        val scene = onPaperUi { ImageComposeScene(if (scale == 1f) 300 else 340, if (scale == 1f) 220 else 400) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, scale)) {
                PaperResearchSourceControlsPreview()
            }
        } }
        var frame = 0L
        fun render() = onPaperUi { repeat(6) { scene.render(++frame * 32_000_000L).close() } }
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        fun nodes() = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
        fun menu() = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Действия с источником") }
        fun title() = nodes().first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "system_design_replit.md · GitHub" } }
        fun pixels(name: String, rect: Rect): IntArray = onPaperUi {
            val bytes = scene.render(++frame * 32_000_000L).use { image -> image.encodeToData()!!.use { it.bytes } }
            val file = File("build/reports/research-source-controls/$name-$scale.png").apply { parentFile.mkdirs() }
            file.writeBytes(bytes)
            ImageIO.read(ByteArrayInputStream(bytes)).getRGB(rect.left.toInt(), rect.top.toInt(), rect.width.toInt(), rect.height.toInt(), null, 0, rect.width.toInt())
        }
        fun move(point: Offset) { onPaperUi { scene.sendPointerEvent(PointerEventType.Move, point, type = PointerType.Mouse) }; render() }
        fun press(key: Key) { onPaperUi {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown)); scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
        }; render() }
        try {
            render()
            val titleBounds = onPaperUi { title().boundsInRoot }
            val menuBounds = onPaperUi { menu().boundsInRoot }
            val checkbox = onPaperUi { nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                .contains("Использовать источник: system_design_replit.md · GitHub") }.boundsInRoot }
            assertTrue(titleBounds.right <= menuBounds.left, "The title cannot overlap its menu")
            assertEquals(checkbox.center.y, menuBounds.center.y, 1f, "Menu aligns with the top checkbox: check=$checkbox menu=$menuBounds title=$titleBounds at $scale")
            val hidden = pixels("idle", menuBounds)
            move(titleBounds.center)
            assertFalse(hidden.contentEquals(pixels("hover", menuBounds)), "Hovering the source must reveal its menu")
            onPaperUi { assertEquals(titleBounds, title().boundsInRoot) }
            move(Offset(10f, 205f))
            assertContentEquals(hidden, pixels("pointer-left", menuBounds), "The menu must hide when the pointer leaves")
            repeat(6) { if (!onPaperUi { menu().config.getOrNull(SemanticsProperties.Focused) == true }) press(Key.Tab) }
            onPaperUi { assertTrue(menu().config.getOrNull(SemanticsProperties.Focused) == true) }
            assertFalse(hidden.contentEquals(pixels("keyboard-focus", menuBounds)))
            press(Key.Enter)
            onPaperUi { assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Открыть" } }) }
            onPaperUi {
                assertTrue(nodes().first { it.config.contains(SemanticsActions.RequestFocus) && walk(it).any { child ->
                    child.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Открыть" }
                } }.config[SemanticsActions.RequestFocus].action!!.invoke())
            }
            render()
            press(Key.Escape)
            onPaperUi { assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Открыть" } }, "Escape closes the source menu") }
            // Arrow keys on the group header toggle the disclosure, not the file action.
            onPaperUi {
                assertTrue(nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Свернуть: Общие для чата") }
                    .config[SemanticsActions.RequestFocus].action!!.invoke(), "Header accepts keyboard focus")
            }
            render()
            press(Key.DirectionLeft)
            onPaperUi { assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "system_design_replit.md · GitHub" } }) }
            press(Key.DirectionRight)
            onPaperUi { assertTrue(title().boundsInRoot.height > 0) }
        } finally { onPaperUi { scene.close() } }
        }
    }
}
