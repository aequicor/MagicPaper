package io.aequicor.magicpaper.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.use
import io.aequicor.magicpaper.designsystem.*
import java.awt.EventQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class VisitPresentationRenderTest {
    @Test fun realRememberSaveableRestoresAfterCompositionAndOwnerRecreation() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try { onUi {
            var snapshot: String? = null
            val owner = VisitPresentationState(null) { snapshot = it }
            scene(owner).use { scene ->
                scene.draw()
                val action = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                    .first { it.config.getOrNull(SemanticsActions.OnClick) != null }
                assertTrue(action.config[SemanticsActions.OnClick].action!!.invoke())
                scene.draw()
                assertTrue(scene.contains("Развёрнуто"))
                owner.flush()
            }
            assertNotNull(snapshot)
            val restored = VisitPresentationState(snapshot) { snapshot = it }
            scene(restored).use { scene ->
                scene.draw()
                assertTrue(scene.contains("Развёрнуто"))
                assertFalse(scene.contains("Свёрнуто"))
            }
            assertNull(restored.restoreError)
        } } finally { Dispatchers.resetMain() }
    }
    private fun scene(owner: VisitPresentationState) = ImageComposeScene(400, 300) {
        PaperTheme { owner.Content { Panel() } }
    }
    @Composable private fun Panel() {
        var expanded by rememberSaveable { mutableStateOf(false) }
        Column {
            PaperAction({ expanded = !expanded }) { PaperText("Раскрыть") }
            PaperText(if (expanded) "Развёрнуто" else "Свёрнуто")
        }
    }
    private fun ImageComposeScene.draw() = repeat(5) { render(it * 16_000_000L).close() }
    private fun ImageComposeScene.contains(text: String) = semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
        .any { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true }
    private fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)

    private fun <T> onUi(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
