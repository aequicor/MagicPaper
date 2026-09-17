package io.aequicor.magicpaper.designsystem

import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.ui.window.LocalWindowToolbarHeight
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class PaperAppTitleBarTest {
    @Test fun compactTitleBarHasOnlySidebarBrandAndActionsAndDisappearsInFullscreen() {
        for ((width, scale) in listOf(640 to 1f, 320 to 1f, 480 to 2f)) {
            var fullscreen by mutableStateOf(false)
            var expanded by mutableStateOf(true)
            var actionClicks = 0
            val scene = onPaperUi { ImageComposeScene(width, 100) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale),
                    LocalWindowToolbarHeight provides if (fullscreen) 0.dp else 40.dp) { PaperTheme {
                    PaperSurface {
                        PaperAppTitleBar(expanded, { expanded = !expanded }) {
                            PaperIconButton("Расходы", { actionClicks++ }) { PaperText("$", role = PaperTextRole.CHROME) }
                            PaperIconButton("Настройки", { actionClicks++ }) { PaperText("⚙", role = PaperTextRole.CHROME) }
                        }
                    }
                } }
            } }
            var frame = 0L
            fun draw() = repeat(6) { onPaperUi { scene.render(frame++ * 16_000_000L).close() } }
            try {
                draw()
                onPaperUi {
                    assertEquals(3, scene.actions().size)
                    val title = scene.nodes().single { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "MagicPaper" } }
                    assertTrue(title.boundsInRoot.left >= scene.action("Скрыть список сессий").boundsInRoot.right)
                    assertTrue(title.boundsInRoot.right <= scene.action("Расходы").boundsInRoot.left)
                    scene.capture("windowed-$width-$scale")
                    scene.action("Скрыть список сессий").config[SemanticsActions.OnClick].action!!.invoke()
                }
                draw()
                onPaperUi {
                    assertNotNull(scene.action("Показать список сессий"))
                    scene.action("Настройки").config[SemanticsActions.OnClick].action!!.invoke()
                    assertEquals(1, actionClicks)
                    fullscreen = true
                }
                draw()
                onPaperUi {
                    assertTrue(scene.actions().isEmpty(), "Hidden chrome must leave no focus or action targets")
                    assertTrue(scene.nodes().none { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "MagicPaper" } })
                    scene.capture("fullscreen-$width-$scale")
                    fullscreen = false
                }
                draw()
                onPaperUi { assertNotNull(scene.action("Показать список сессий")) }
            } finally { onPaperUi { scene.close() } }
        }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.actions() = nodes().filter { it.config.contains(SemanticsActions.OnClick) }
    private fun ImageComposeScene.action(label: String) = actions().single { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
    private fun ImageComposeScene.capture(name: String) {
        File("build/reports/titlebar/$name.png").apply { parentFile.mkdirs() }
            .writeBytes(render().use { it.encodeToData()!!.use { it.bytes } })
    }
}
