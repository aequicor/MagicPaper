package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.aequicor.magicpaper.data.coding.backendCatalog
import io.aequicor.magicpaper.designsystem.PaperDivider
import io.aequicor.magicpaper.designsystem.PaperSurface
import io.aequicor.magicpaper.domain.CodingEngine
import io.aequicor.magicpaper.domain.RuntimePhase
import io.aequicor.magicpaper.domain.RuntimeStatus
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Claude Code account beside the ChatGPT subscription; renders go to `build/reports/engine-account` for inspection. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class EngineAccountSectionRenderTest {
    private val claude = backendCatalog.descriptors.single { it.engine == CodingEngine.CLAUDE_CODE }

    @Test fun accountStatesRenderAtNarrowAndDesktopWidths() {
        val output = File("build/reports/engine-account").apply { mkdirs() }
        for (width in listOf(360, 720)) {
            ImageComposeScene(width, 980) {
                MagicPaperTheme {
                    PaperSurface {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            EngineAccountSection(claude, RuntimeStatus(RuntimePhase.READY, signedIn = false), false, false, {}, {}, {}, {})
                            PaperDivider()
                            EngineAccountSection(claude, RuntimeStatus(RuntimePhase.READY, signedIn = false), true, false, {}, {}, {}, {})
                            PaperDivider()
                            EngineAccountSection(claude, RuntimeStatus(RuntimePhase.READY, signedIn = true), false, false, {}, {}, {}, {})
                            PaperDivider()
                            EngineAccountSection(claude, RuntimeStatus(RuntimePhase.READY, signedIn = true), false, true, {}, {}, {}, {})
                        }
                    }
                }
            }.use { scene ->
                repeat(4) { scene.render(it * 16_000_000L).close() }
                val file = File(output, "account-$width.png")
                file.writeBytes(scene.render(80_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })
                assertTrue(file.length() > 0)
            }
        }
    }

    /** The CLI reports a stored login even when its token is dead, so a signed-in account offers to leave it. */
    @Test fun signedInAccountOffersTheSignOutAndHoldsItWhileItRuns() {
        var signOuts = 0
        section(RuntimeStatus(RuntimePhase.READY, signedIn = true), signingOut = false) { signOuts++ }.use { scene ->
            button(scene.nodes(), "Выйти")!!.config[SemanticsActions.OnClick].action!!.invoke()
            assertEquals(1, signOuts)
        }
        section(RuntimeStatus(RuntimePhase.READY, signedIn = true), signingOut = true) {}.use { scene ->
            val nodes = scene.nodes()
            assertEquals("Загрузка", button(nodes, "Выйти")!!.config.getOrNull(SemanticsProperties.StateDescription))
            assertTrue(button(nodes, "Выйти")!!.config.contains(SemanticsProperties.Disabled), "A second sign-out cannot start")
            assertTrue(button(nodes, "Обновить")!!.config.contains(SemanticsProperties.Disabled), "A check would answer for the old login")
        }
        section(RuntimeStatus(RuntimePhase.READY, signedIn = false), signingOut = false) {}.use { scene ->
            assertNull(button(scene.nodes(), "Выйти"), "There is nothing to leave")
            assertNotNull(button(scene.nodes(), "Войти в Claude Code"))
        }
    }

    private fun section(status: RuntimeStatus, signingOut: Boolean, onSignOut: () -> Unit) = ImageComposeScene(720, 320) {
        MagicPaperTheme { PaperSurface { EngineAccountSection(claude, status, false, signingOut, {}, {}, {}, onSignOut) } }
    }.also { scene -> repeat(4) { scene.render(it * 16_000_000L).close() } }

    private fun button(nodes: List<SemanticsNode>, label: String) = nodes.firstOrNull { node ->
        node.config.contains(SemanticsActions.OnClick) && node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == label }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
}
