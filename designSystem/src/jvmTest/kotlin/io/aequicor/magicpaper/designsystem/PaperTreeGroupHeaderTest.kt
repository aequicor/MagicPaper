package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class PaperTreeGroupHeaderTest {
    @Test
    fun disclosureActionsAndKeyboardChangeExpansionOnce() {
        val expanded = mutableStateOf(true)
        val focus = FocusRequester()
        var toggles = 0
        ImageComposeScene(320, 120) {
            PaperTheme {
                PaperTreeGroupHeader(
                    "Новая задача",
                    expanded.value,
                    { expanded.value = !expanded.value; toggles++ },
                    Modifier.focusRequester(focus),
                )
            }
        }.use { scene ->
            var frame = 0L
            fun render() {
                Snapshot.sendApplyNotifications()
                repeat(3) { scene.render(++frame * 16_000_000L).close() }
            }
            fun control() = scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Задача: Новая задача")
            }
            fun press(key: Key) {
                assertTrue(scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown)))
                render()
                assertTrue(scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp)))
                render()
            }
            render()
            assertEquals("Развёрнута", control().config[SemanticsProperties.StateDescription])
            val collapse = assertNotNull(control().config.getOrNull(SemanticsActions.OnClick))
            assertEquals("Свернуть задачу", collapse.label)
            assertEquals(true, collapse.action?.invoke())
            render()
            assertEquals(1, toggles)
            assertEquals("Свёрнута", control().config[SemanticsProperties.StateDescription])
            assertEquals("Раскрыть задачу", control().config[SemanticsActions.OnClick].label)
            assertTrue(focus.requestFocus())
            render()
            press(Key.Enter)
            assertEquals(2, toggles)
            assertTrue(expanded.value)
            press(Key.Spacebar)
            assertEquals(3, toggles)
            assertTrue(!expanded.value)
            press(Key.DirectionRight)
            assertEquals(4, toggles)
            assertTrue(expanded.value)
            press(Key.DirectionRight)
            assertEquals(4, toggles, "Right must keep an expanded group open")
            press(Key.DirectionLeft)
            assertEquals(5, toggles)
            assertTrue(!expanded.value)
            press(Key.DirectionLeft)
            assertEquals(5, toggles, "Left must keep a collapsed group closed")
        }
    }

    @Test
    fun narrowHeaderRetainsDisclosureStatusAndFullTitleAtEveryTextScale() {
        val title = "Длинное название задачи с иммунитетом и зиготой"
        for (platform in listOf(PaperPlatform.MACOS, PaperPlatform.WINDOWS)) {
            for (fontScale in listOf(1f, 1.25f, 1.5f, 2f)) {
                val rowHeight = if (platform == PaperPlatform.MACOS) 28.dp else 32.dp
                val policy = PaperPlatformPolicy.Fallback.copy(
                    platform = platform,
                    density = PaperDensity(rowHeight, rowHeight, rowHeight + 2.dp, 16.dp),
                )
                var status = Rect.Zero
                ImageComposeScene(200, 160) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        PaperTheme {
                            CompositionLocalProvider(LocalPaperPlatformPolicy provides policy) {
                                PaperSurface(Modifier.fillMaxSize(), PaperSurfaceKind.CANVAS) {
                                    Column {
                                        PaperTreeGroupHeader(
                                            title,
                                            expanded = true,
                                            onToggle = {},
                                            modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
                                            active = true,
                                            leading = {
                                                Box(Modifier.size(10.dp).onGloballyPositioned { status = it.boundsInRoot() })
                                            },
                                        )
                                        PaperTreeGroupHeader(
                                            "Ещё одна задача",
                                            expanded = false,
                                            onToggle = {},
                                            modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }.use { scene ->
                    repeat(12) { scene.render((it + 1) * 16_000_000L).close() }
                    val nodes = scene.nodes()
                    val control = nodes.single {
                        it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Задача: $title")
                    }
                    val titleNode = nodes.single {
                        it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == title
                    }
                    val bounds = control.boundsInRoot
                    assertEquals(true, control.config[SemanticsProperties.Selected])
                    val text = titleNode.boundsInRoot
                    val layouts = mutableListOf<TextLayoutResult>()
                    assertTrue(titleNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
                    val layout = layouts.single()
                    if (fontScale == 1f || fontScale == 2f) {
                        val file = File("build/reports/paper-task-group-header/${platform.name.lowercase()}-${(fontScale * 100).toInt()}.png")
                        file.parentFile.mkdirs()
                        scene.render(224_000_000L).use { image ->
                            image.encodeToData()!!.use { file.writeBytes(it.bytes) }
                        }
                    }
                    assertEquals(1, layout.lineCount)
                    // Compose 1.11.1 SkiaParagraph.isLineEllipsized is an unconditional
                    // false stub. Assert actual line geometry and the requested overflow
                    // instead; saved renders also expose the visible ellipsis for review.
                    val geometry = "platform=$platform fontScale=$fontScale control=$bounds " +
                        "text=$text status=$status layoutSize=${layout.size} " +
                        "constraints=${layout.layoutInput.constraints}"
                    assertEquals(TextOverflow.Ellipsis, layout.layoutInput.overflow)
                    assertTrue(layout.multiParagraph.intrinsics.maxIntrinsicWidth > text.width,
                        "This case must exercise a title wider than the available space; $geometry")
                    assertTrue(layout.getLineLeft(0) >= 0f && layout.getLineRight(0) <= text.width + 1f,
                        "The rendered title line must fit inside its allocated width; $geometry")
                    assertTrue(bounds.height >= rowHeight.value)
                    assertTrue(bounds.left >= 20f && bounds.right <= 192f)
                    assertTrue(status.left >= bounds.left + 24f, "Disclosure must retain space before the status")
                    assertTrue(status.right < text.left && text.right <= bounds.right)
                    assertTrue(text.top >= bounds.top && text.bottom <= bounds.bottom)
                    assertTrue(abs(text.center.y - status.center.y) <= 1f, "Title and status share the vertical centre")
                }
            }
        }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
}
