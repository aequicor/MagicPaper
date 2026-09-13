package io.aequicor.magicpaper.tools.paper

import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.snapshots.Snapshot
import io.aequicor.visualization.engine.ir.model.PropValue
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class PaperPreviewInteractionTest {
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.frame() { Snapshot.sendApplyNotifications(); render(64_000_000L).close() }
    @Test fun pointerAndKeyboardUseLocalFixtureStateWithoutMutatingAuthoredProps() {
        val value = paperFixtures.single { it.definition.id == "PaperButton" }.definition.instance("paper")
        ImageComposeScene(280, 72) { PaperFixturePreview(value) }.use { scene ->
            scene.frame()
            scene.sendPointerEvent(PointerEventType.Press, Offset(40f, 20f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, Offset(40f, 20f), type = PointerType.Mouse)
            scene.frame()
            assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Нажато: 1") })
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyUp))
            scene.frame()
            assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Нажато: 2") })
            assertEquals(PropValue.Text("Пример Paper"), value.props["text"])
        }
    }
    @Test fun fieldEditsAreLocalAndDisabledButtonCannotActivate() {
        val field = paperFixtures.single { it.definition.id == "PaperField" }.definition.instance("paper")
        ImageComposeScene(280, 116) { PaperFixturePreview(field) }.use { scene ->
            scene.frame()
            val node = scene.nodes().first { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertTrue(node.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Changed")))
            scene.frame()
            assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Changed" })
            assertEquals(PropValue.Text("Пример Paper"), field.props["text"])
        }
        val button = paperFixtures.single { it.definition.id == "PaperButton" }.definition.instance("paper").let { it.copy(variant = it.variant + ("state" to "DISABLED")) }
        ImageComposeScene(280, 72) { PaperFixturePreview(button) }.use { scene ->
            scene.frame()
            val node = scene.nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription) != null }
            assertTrue(node.config.contains(SemanticsProperties.Disabled))
            scene.sendPointerEvent(PointerEventType.Press, Offset(40f, 20f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, Offset(40f, 20f), type = PointerType.Mouse)
            scene.frame()
            assertTrue(scene.nodes().none { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { label -> label.startsWith("Нажато:") } == true })
        }
    }
    @Test fun menusAndDialogsOpenAndDismissInsideTheIsolatedPreview() {
        for (id in listOf("PaperMenu", "PaperDialog")) {
            val value = paperFixtures.single { it.definition.id == id }.definition.instance("paper")
            ImageComposeScene(480, 360) { PaperFixturePreview(value) }.use { scene ->
                scene.frame()
                val opener = scene.nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Пример Paper") }
                assertTrue(opener.config[SemanticsActions.OnClick].action!!.invoke())
                scene.frame()
                fun hasText(label: String) = scene.nodes().any { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
                assertTrue(hasText(if (id == "PaperMenu") "Первый пункт" else "Закрыть"), id)
                // Native popup key dispatch is outside ImageComposeScene. Exercise each
                // visible completion action here; Enter activation is tested above.
                val label = if (id == "PaperMenu") "Первый пункт" else "Закрыть"
                var actionNode = scene.nodes().first { n ->
                    n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
                }
                while (actionNode.config.getOrNull(SemanticsActions.OnClick) == null) {
                    actionNode = assertNotNull(actionNode.parent)
                }
                assertTrue(actionNode.config[SemanticsActions.OnClick].action!!.invoke())
                scene.frame()
                // Popup exit animations need advancing frame timestamps, not a fixed snapshot.
                repeat(24) { frame -> scene.render(80_000_000L + frame * 16_000_000L).close() }
                assertFalse(hasText(if (id == "PaperMenu") "Первый пункт" else "Закрыть"), id)
            }
        }
    }

}
