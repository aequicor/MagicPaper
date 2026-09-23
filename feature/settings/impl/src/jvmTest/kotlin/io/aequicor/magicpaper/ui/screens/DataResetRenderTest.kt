package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class DataResetRenderTest {
    // An erase that cannot be undone runs only from its confirmation; the action itself and "Отмена" erase nothing.
    @Test fun wipeRunsOnlyAfterConfirmationAtDesktopAndPhoneWidths() {
        val output = File("build/reports/data-reset").apply { mkdirs() }
        for (width in listOf(390, 1100)) {
            var wipes = 0
            ImageComposeScene(width, 900) {
                MagicPaperTheme { Surface { Column(Modifier.fillMaxSize().padding(16.dp)) { WipeDataAction { wipes++ } } } }
            }.use { scene ->
                var time = 0L
                // Enough frames for the dialog's entrance to settle before a render is judged.
                fun frame() { repeat(24) { scene.render(time).close(); time += 16_000_000L } }
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                fun labelled(node: SemanticsNode, text: String) = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
                fun nodes() = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                fun withText(text: String) = nodes().filter { labelled(it, text) }
                // The action and the dialog's destructive button share a label; the dialog's lives in the owner with the title.
                fun inDialog(text: String) = scene.semanticsOwners.map { walk(it.unmergedRootSemanticsNode) }
                    .single { owner -> owner.any { labelled(it, "Стереть данные?") } }.single { labelled(it, text) }
                fun click(node: SemanticsNode) {
                    val center = node.boundsInRoot.center
                    scene.sendPointerEvent(PointerEventType.Press, center)
                    scene.sendPointerEvent(PointerEventType.Release, center)
                    frame()
                }
                frame()
                click(withText("Стереть данные").single())
                assertTrue(withText("Стереть данные?").isNotEmpty(), "the action opens its confirmation")
                assertEquals(0, wipes, "opening the confirmation erases nothing")
                scene.render(time).use { File(output, "confirm-$width.png").writeBytes(it.encodeToData()!!.use { data -> data.bytes }) }
                click(withText("Отмена").single())
                assertTrue(withText("Стереть данные?").isEmpty())
                assertEquals(0, wipes, "cancel erases nothing")
                click(withText("Стереть данные").single())
                click(inDialog("Стереть данные"))
                assertEquals(1, wipes)
                assertTrue(withText("Стереть данные?").isEmpty(), "the dialog closes once the erase is requested")
            }
        }
    }
}
