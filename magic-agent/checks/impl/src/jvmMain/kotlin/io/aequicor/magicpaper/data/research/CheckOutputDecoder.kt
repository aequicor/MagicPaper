package io.aequicor.magicpaper.data.research

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Check output arrives as bytes in whatever encoding each program chose. Most tools write UTF-8, but on Windows
 * `cmd.exe` and other console programs write in the console's OEM code page (CP866 on a Russian system), and read as
 * UTF-8 their messages became unreadable. Output is decoded line by line, since a line comes from one program: a line
 * that is valid UTF-8 is read as UTF-8 and any other in [fallback]; without one it keeps UTF-8's replacement characters.
 * A carriage return ends a line too, so progress written in place still reaches the caller as it happens.
 */
internal class CheckOutputDecoder(private val fallback: Charset?) {
    private val pending = ByteArrayOutputStream()

    /** Text of the lines [bytes] completes; an unfinished line waits for its end or for [finish]. */
    fun accept(bytes: ByteArray, count: Int): String = buildString {
        for (index in 0 until count) {
            val byte = bytes[index]
            pending.write(byte.toInt())
            if (byte == LF || byte == CR) append(flush(pending.size()))
            else if (pending.size() >= LINE_LIMIT) append(flush(pending.size() - incompleteUtf8Tail()))
        }
    }

    fun finish(): String = flush(pending.size())

    private fun flush(length: Int): String {
        val all = pending.toByteArray()
        pending.reset()
        pending.write(all, length, all.size - length)
        if (length == 0) return ""
        val line = all.copyOf(length)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(line)).toString()
        } catch (notUtf8: CharacterCodingException) {
            String(line, fallback ?: Charsets.UTF_8)
        }
    }

    /** Bytes at the end of a long line that begin a UTF-8 character not yet complete; they wait for the next read. */
    private fun incompleteUtf8Tail(): Int {
        val bytes = pending.toByteArray()
        for (back in 1..minOf(3, bytes.size)) {
            val value = bytes[bytes.size - back].toInt() and 0xFF
            if (value and 0xC0 == 0x80) continue
            val needed = when { value and 0xE0 == 0xC0 -> 2; value and 0xF0 == 0xE0 -> 3; value and 0xF8 == 0xF0 -> 4; else -> 1 }
            return if (needed > back) back else 0
        }
        return 0
    }

    companion object {
        /** A line longer than this is delivered in pieces, split between whole UTF-8 characters. */
        const val LINE_LIMIT = 4096
        private const val LF = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()
    }
}
