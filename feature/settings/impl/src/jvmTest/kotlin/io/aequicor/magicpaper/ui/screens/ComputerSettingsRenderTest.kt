package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.ComputerPermissionUi
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class ComputerSettingsRenderTest {
    @Test fun onboardingStatesRenderAndOnlyExplicitActionsOpenSystemWindows() {
        val out = File("build/reports/computer-permissions").apply { mkdirs() }
        val cases = listOf("missing", "granted", "loading", "error", "windows", "off", "unsupported")
        for (name in cases) for (scale in listOf(1f, 2f)) {
            val state = when (name) {
                "granted" -> permissionPreviewState(true)
                "loading" -> permissionPreviewState().copy(busy = true)
                "error" -> permissionPreviewState().copy(error = "Не удалось проверить разрешения. Повторите проверку.")
                "windows" -> ComputerPermissionUi(ComputerPermissionReport(PermissionPlatform.WINDOWS))
                "off" -> ComputerPermissionUi(ComputerPermissionReport(PermissionPlatform.MACOS))
                "unsupported" -> ComputerPermissionUi(ComputerPermissionReport())
                else -> permissionPreviewState()
            }
            val opened = mutableListOf<ComputerPermission>()
            val revealed = mutableListOf<PermissionTarget>()
            var refreshed = 0
            ImageComposeScene(390, 2600, density = Density(1f, scale)) { PaperTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    ComputerPermissionOnboarding(state, { refreshed++ }, { opened += it }, { revealed += it })
                }
            } }.use { scene ->
                fun render() { repeat(4) { scene.render(it * 32_000_000L).close() } }
                fun nodes(): List<SemanticsNode> {
                    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                    return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                }
                fun texts(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::texts)
                fun button(label: String): SemanticsNode = nodes().single {
                    it.config.contains(SemanticsActions.OnClick) && label in texts(it)
                }
                fun click(label: String) {
                    val bounds = button(label).boundsInRoot
                    assertTrue(bounds.left >= 0 && bounds.right <= 390 && bounds.top >= 0 && bounds.bottom <= 2600)
                    scene.sendPointerEvent(PointerEventType.Press, bounds.center)
                    scene.sendPointerEvent(PointerEventType.Release, bounds.center)
                    render()
                }
                render()
                assertTrue(opened.isEmpty()); assertTrue(revealed.isEmpty()); assertEquals(0, refreshed)
                val checks = nodes().filter { it.config.contains(SemanticsActions.OnClick) }
                assertTrue(checks.all { it.boundsInRoot.left >= 0 && it.boundsInRoot.right <= 390 })
                if (name in listOf("missing", "granted", "error")) {
                    click("Открыть запись экрана")
                    click("Открыть Универсальный доступ")
                    assertEquals(ComputerPermission.entries.toList(), opened)
                    click("Показать в Finder: MagicPaper.app")
                    assertEquals("/Applications/MagicPaper.app", revealed.single().path)
                } else if (name == "loading") {
                    assertTrue(button("Проверить").config.contains(SemanticsProperties.Disabled))
                } else {
                    assertTrue(checks.none { texts(it).any { text -> text.startsWith("Открыть") } })
                }
                if (name != "loading") { click("Проверить"); assertEquals(1, refreshed) }
                scene.render(800_000_000).use { image -> File(out, "$name-$scale.png").writeBytes(image.encodeToData()!!.use { it.bytes }) }
            }
        }
    }

    @Test fun fullScreenIsScrollableWithoutSavingOrLaunchingOnRender() {
        val out = File("build/reports/computer-permissions").apply { mkdirs() }
        for (width in listOf(390, 900)) ImageComposeScene(width, 1000) { PaperTheme {
            ComputerSettingsContent(AppSettings(), true, true, false, permissionPreviewState(),
                {}, { error("render saved settings") }, { error("render refreshed") }, { error("render launched") }, { error("render revealed") })
        } }.use { scene ->
            repeat(4) { scene.render(it * 32_000_000L).close() }
            scene.render(500_000_000).use { image -> File(out, "screen-$width.png").writeBytes(image.encodeToData()!!.use { it.bytes }) }
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            fun nodes() = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            val scroll = nodes().single { it.config.contains(SemanticsActions.ScrollBy) }
            assertTrue(scroll.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0)
            assertTrue(scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 5000f))
            repeat(12) { scene.render(600_000_000L + it * 100_000_000L).close() }
            val refresh = nodes().single { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Проверить" } }
            assertTrue(refresh.boundsInRoot.top >= 0 && refresh.boundsInRoot.bottom <= 1000)
            scene.render(2_000_000_000).use { image -> File(out, "screen-bottom-$width.png").writeBytes(image.encodeToData()!!.use { it.bytes }) }
        }
    }
}
