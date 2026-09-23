package io.aequicor.magicpaper.logging

import kotlin.test.*

/**
 * A coroutine stack is deeper than the frame window, so the causes that explain a failure used to fall
 * past it: a refused check logged only its wrapper («Команда или рабочая папка проверки недоступна»),
 * not the reason underneath. Their headers survive the window; their frames stay bounded.
 */
class AppLoggerCauseChainTest {
    private fun deep(frames: Int) = Array(frames) { StackTraceElement("kotlinx.coroutines.Deep", "frame$it", "Deep.kt", it) }

    @Test fun causesBeyondTheFrameWindowKeepTheirHeadersRedacted() {
        val root = IllegalArgumentException("gradlew не является исполнимым файлом Windows; token=private-value")
        val cleanup = IllegalStateException("cleanup failed")
        val top = IllegalStateException("Команда или рабочая папка проверки недоступна", root).apply {
            stackTrace = deep(60)
            addSuppressed(cleanup)
        }
        root.stackTrace = deep(60)
        cleanup.stackTrace = deep(60)
        val log = AppLogger(sink = AppLogSink {})
        log.error("checks", "run.failed", top)
        val stack = log.history().single().causeStack!!
        assertContains(stack, "Caused by: java.lang.IllegalArgumentException: gradlew не является исполнимым файлом Windows")
        assertContains(stack, "Suppressed: java.lang.IllegalStateException: cleanup failed")
        assertFalse("private-value" in stack, stack)
        assertTrue(stack.lines().size <= 1 + 40 + 6, "Header, at most 40 frames and at most six cause headers")
    }
}
