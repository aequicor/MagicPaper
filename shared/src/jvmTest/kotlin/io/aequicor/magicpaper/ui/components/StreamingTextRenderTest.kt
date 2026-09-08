package io.aequicor.magicpaper.ui.components

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamingTextRenderTest {
    @Test fun incomingChunksDoNotRestartTheClockAndCompletionFlushesTheSuffix() {
        val target = mutableStateOf("Начало ответа. ")
        val streaming = mutableStateOf(true)
        val session = mutableStateOf("first")
        var displayed = ""
        ImageComposeScene(100, 100) {
            key(session.value) {
                val text = rememberStreamingText(target.value, streaming.value)
                SideEffect { displayed = text }
            }
        }.use { scene ->
            var frame = 0L
            fun render() {
                scene.render(++frame * 16_000_000L).close()
                Thread.sleep(2)
            }
            repeat(4) { render() }
            assertEquals(target.value, displayed, "Opening an existing response must not replay it")
            val initial = displayed
            target.value += "Продолжение ответа. ".repeat(30)
            repeat(4) { render() }
            assertTrue(displayed.length > initial.length, "The burst must start appearing")
            assertTrue(displayed.length < target.value.length, "The burst must have intermediate frames")

            var previous = displayed
            var updates = 0
            repeat(60) {
                target.value += " ещё текст"
                render()
                assertTrue(displayed.startsWith(previous), "Previously visible text must stay visible")
                if (displayed != previous) updates++
                previous = displayed
            }
            assertTrue(updates >= 20, "Continuous input must not starve the frame clock: $updates updates")
            assertTrue(displayed.length < target.value.length)
            streaming.value = false
            repeat(2) { render() }
            assertEquals(target.value, displayed, "Finishing must show all remaining text immediately")

            streaming.value = true
            session.value = "second"
            target.value += " История другой сессии."
            repeat(2) { render() }
            assertEquals(target.value, displayed, "Switching sessions must not animate an existing prefix")
        }
    }
}
