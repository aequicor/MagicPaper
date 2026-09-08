package io.aequicor.magicpaper.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamingTextBufferTest {
    @Test fun aLargeBurstAppearsProgressivelyAndCatchesUpQuickly() {
        val initial = "Уже видимый ответ. "
        val target = initial + "Новый фрагмент ответа. ".repeat(200)
        val buffer = StreamingTextBuffer(initial)
        buffer.update(target, streaming = true)
        assertEquals(initial, buffer.text)
        var previous = initial
        repeat(4) {
            buffer.advance(32.0)
            assertTrue(buffer.text.startsWith(previous))
            assertTrue(buffer.text.length > previous.length)
            assertTrue(buffer.text.length < target.length)
            previous = buffer.text
        }
        buffer.advance(33.0)
        assertEquals(target, buffer.text)
        assertFalse(buffer.hasPendingText)
    }

    @Test fun continuousFastInputDoesNotBuildUpAnAnimationQueue() {
        val buffer = StreamingTextBuffer("")
        var target = ""
        repeat(120) {
            repeat(8) {
                target += "Текст "
                buffer.update(target, streaming = true)
            }
            val previousLength = buffer.text.length
            buffer.advance(32.0)
            assertTrue(buffer.text.length > previousLength, "Even a constant stream must make progress")
            assertTrue(target.length - buffer.text.length < 300, "The pending suffix must stay bounded")
        }
        buffer.advance(161.0)
        assertEquals(target, buffer.text)
    }

    @Test fun completionAndAuthoritativeCorrectionsFlushImmediately() {
        val buffer = StreamingTextBuffer("Ответ")
        buffer.update("Ответ" + " продолжение".repeat(100), streaming = true)
        buffer.advance(32.0)
        buffer.update("Исправленный ответ", streaming = true)
        assertEquals("Исправленный ответ", buffer.text)
        assertFalse(buffer.hasPendingText)

        buffer.update("Исправленный ответ с окончанием", streaming = true)
        assertTrue(buffer.hasPendingText)
        buffer.update("Исправленный ответ с окончанием", streaming = false)
        assertEquals("Исправленный ответ с окончанием", buffer.text)
        assertFalse(buffer.hasPendingText)

        buffer.update("", streaming = true)
        assertEquals("", buffer.text)
        assertFalse(buffer.hasPendingText)
    }

    @Test fun emojiNeverAppearsAsHalfASurrogatePair() {
        val buffer = StreamingTextBuffer("")
        buffer.update("✦🪄✨", streaming = true)
        val frames = mutableListOf<String>()
        repeat(8) {
            buffer.advance(16.0)
            frames += buffer.text
        }
        assertTrue(frames.all { it in listOf("", "✦", "✦🪄", "✦🪄✨") })
        assertEquals("✦🪄✨", buffer.text)
    }
}
