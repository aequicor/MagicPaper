package io.aequicor.magicpaper.designsystem

import kotlin.io.encoding.Base64
import kotlin.test.*

class PaperMediaImageDecodingTest {
    private val png = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")

    @Test fun realPngIsDecodedWithoutProviderDimensionMetadata() {
        val image = decodePaperMediaImage(png, "image/png")
        assertEquals(1, image.width)
        assertEquals(1, image.height)
    }

    @Test fun mismatchedSignatureAndOversizedHeadersAreRejectedBeforeAllocatingPixels() {
        assertFailsWith<IllegalArgumentException> { decodePaperMediaImage(png, "image/jpeg") }
        val enormous = png.copyOf().apply {
            // Valid PNG signature, a width exceeding the entire allowed pixel budget.
            this[16] = 0x7f; this[17] = 0xff.toByte(); this[18] = 0xff.toByte(); this[19] = 0xff.toByte()
        }
        assertFailsWith<IllegalArgumentException> { decodePaperMediaImage(enormous, "image/png") }
        assertFailsWith<IllegalArgumentException> { decodePaperMediaImage(png.copyOf(20), "image/png") }
        assertFailsWith<IllegalArgumentException> { decodePaperMediaImage(ByteArray(12 * 1024 * 1024 + 1), "image/png") }
        assertFailsWith<IllegalArgumentException> { decodePaperMediaImage("<svg/>".encodeToByteArray(), "image/svg+xml") }
    }
}
