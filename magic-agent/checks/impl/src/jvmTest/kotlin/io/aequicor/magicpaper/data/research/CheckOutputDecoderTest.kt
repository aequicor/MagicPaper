package io.aequicor.magicpaper.data.research

import java.nio.charset.Charset
import kotlin.test.*

/**
 * Programs write check output in their own encoding: Git and Node write UTF-8, but on Windows `cmd.exe` writes its
 * messages in the console's OEM code page. Read as UTF-8, «Системе не удается найти указанный путь.» in CP866 reached
 * the user as «���⥬� �� 㤠���� ���� 㪠����� ����.». A line that is not valid UTF-8 is read in the fallback instead.
 */
class CheckOutputDecoderTest {
    private val oem = Charset.forName("IBM866")
    private fun decode(decoder: CheckOutputDecoder, vararg chunks: ByteArray) =
        chunks.joinToString("") { decoder.accept(it, it.size) } + decoder.finish()

    @Test fun cmdMessageInTheConsoleCodePageIsReadInIt() {
        val message = "Системе не удается найти указанный путь.\r\n"
        assertEquals(message, decode(CheckOutputDecoder(oem), message.toByteArray(oem)))
    }

    @Test fun eachLineKeepsItsOwnEncoding() {
        val git = "На ветке main — всё чисто\n".toByteArray(Charsets.UTF_8)
        val cmd = "Системе не удается найти указанный путь.\r\n".toByteArray(oem)
        assertEquals("На ветке main — всё чисто\nСистеме не удается найти указанный путь.\r\n",
            decode(CheckOutputDecoder(oem), git + cmd))
    }

    @Test fun utf8CharacterSplitBetweenReadsIsNotMistakenForTheFallback() {
        val bytes = "Тест пройден ✓\n".toByteArray(Charsets.UTF_8)
        val decoder = CheckOutputDecoder(oem)
        val text = bytes.indices.joinToString("") { decoder.accept(byteArrayOf(bytes[it]), 1) } + decoder.finish()
        assertEquals("Тест пройден ✓\n", text)
    }

    @Test fun lineLongerThanTheBufferKeepsItsCharactersWhole() {
        val line = "я".repeat(CheckOutputDecoder.LINE_LIMIT) + "\n"
        assertEquals(line, decode(CheckOutputDecoder(oem), line.toByteArray(Charsets.UTF_8)))
    }

    @Test fun partialLineIsDeliveredOnCarriageReturnForProgress() {
        val decoder = CheckOutputDecoder(oem)
        assertEquals("45%\r", decoder.accept("45%\r".toByteArray(), 4))
        assertEquals("", decoder.finish())
    }

    @Test fun withoutAFallbackInvalidBytesStayVisibleAsReplacements() {
        val text = decode(CheckOutputDecoder(null), "Путь\n".toByteArray(oem))
        assertTrue(text.endsWith("\n") && '�' in text, text)
    }
}
