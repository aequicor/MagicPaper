package io.aequicor.magicpaper.logging

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformLogEncodingTest {
    private val message = "ОС не подтвердила защиту исходников. Проверка недоступна"

    /** A console code page without Cyrillic must not be able to destroy the recorded cause. */
    @Test fun causeTextSurvivesAConsoleCharsetThatCannotRepresentIt() {
        val captured = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(captured, true, "US-ASCII"))
        try { platformWriteLog("{\"causeMessage\":\"$message\"}", error = false) } finally { System.setOut(original) }
        assertEquals("{\"causeMessage\":\"$message\"}", captured.toString(Charsets.UTF_8).trimEnd())
        assertTrue('?' !in captured.toString(Charsets.UTF_8), "a replaced character cannot be read back")
    }

    @Test fun errorDiagnosticsUseTheSameEncodingAsOrdinaryOnes() {
        val captured = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(captured, true, "US-ASCII"))
        try { platformWriteLog(message, error = true) } finally { System.setErr(original) }
        assertEquals(message, captured.toString(Charsets.UTF_8).trimEnd())
    }
}
