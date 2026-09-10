package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import java.awt.EventQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class PaperContentEntranceTest {
    private fun <T> onUi(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    @Test fun newContentRevealsOnceAndSavedIdentityDoesNotReplayItsEntrance() {
        val shown = mutableStateOf(false)
        val animate = mutableStateOf(true)
        val label = mutableStateOf("Started")
        var height = 0
        var frame = 0L
        var compositions = 0
        val scene = onUi { ImageComposeScene(300, 300) {
            PaperTheme {
                val saved = rememberSaveableStateHolder()
                Column(Modifier.onSizeChanged { height = it.height }) {
                    if (shown.value) saved.SaveableStateProvider("command") {
                        PaperContentEntrance(animate.value) {
                            remember { compositions++ }
                            Box(Modifier.size(120.dp, 80.dp)) { PaperText(label.value) }
                        }
                    }
                }
            }
        } }
        fun render(): List<Int> = buildList {
            repeat(16) {
                add(onUi { scene.render(++frame * 32_000_000L).close(); height })
                Thread.sleep(5)
            }
        }
        try {
            render()
            onUi { shown.value = true }
            val arrival = render()
            assertEquals(80, arrival.last())
            assertTrue(arrival.any { it in 1 until 80 }, "The entrance must contain intermediate heights: $arrival")
            assertTrue(arrival.zipWithNext().all { (before, after) -> after >= before })
            val before = compositions
            onUi { animate.value = false; label.value = "Done" }
            assertTrue(render().all { it == 80 }, "Updating content must not remove or shrink it")
            assertEquals(before, compositions, "Updating content preserves its composition")
            onUi { shown.value = false }
            render()
            onUi { animate.value = true; shown.value = true }
            assertTrue(render().all { it == 80 }, "Restoring a saved identity must not replay the entrance")
        } finally { onUi { scene.close() } }
    }

    @Test fun existingContentIsVisibleAtItsFirstLayout() {
        var height = 0
        val scene = onUi { ImageComposeScene(300, 300) {
            PaperTheme {
                Column(Modifier.onSizeChanged { height = it.height }) {
                    PaperContentEntrance(animate = false) { Box(Modifier.size(120.dp, 80.dp)) }
                }
            }
        } }
        try {
            onUi { scene.render(32_000_000L).close() }
            assertEquals(80, height)
        } finally { onUi { scene.close() } }
    }
}
