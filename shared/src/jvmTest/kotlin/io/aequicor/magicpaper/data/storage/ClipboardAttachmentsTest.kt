package io.aequicor.magicpaper.data.storage

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class ClipboardAttachmentsTest {
    @Test
    fun textUsesNormalPaste() {
        assertNull(clipboardFileReader(StringSelection("hello"), 1024))
    }

    @Test
    fun screenshotIsEncodedAsPng() = runTest {
        val image = BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0x80ff0000.toInt())
        val reader = assertNotNull(clipboardFileReader(content(DataFlavor.imageFlavor, image), 1024))
        val file = reader().single()
        assertEquals("image/png", file.mimeType)
        val decoded = ImageIO.read(ByteArrayInputStream(file.bytes))
        assertEquals(2, decoded.width)
        assertEquals(3, decoded.height)
        assertEquals(image.getRGB(0, 0), decoded.getRGB(0, 0))
    }

    @Test
    fun filesKeepNamesAndContentsAndEnforceSizeLimit() = runTest {
        val path = Files.createTempFile("clipboard-", ".txt")
        try {
            Files.writeString(path, "hello")
            val clipboard = content(DataFlavor.javaFileListFlavor, listOf(path.toFile()))
            val file = assertNotNull(clipboardFileReader(clipboard, 5))().single()
            assertEquals(path.fileName.toString(), file.name)
            assertEquals("hello", file.bytes.decodeToString())
            assertEquals("text/plain", file.mimeType)
            assertFailsWith<IllegalArgumentException> {
                assertNotNull(clipboardFileReader(clipboard, 4))()
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun content(flavor: DataFlavor, value: Any) = object : Transferable {
        override fun getTransferDataFlavors() = arrayOf(flavor)
        override fun isDataFlavorSupported(candidate: DataFlavor) = candidate == flavor
        override fun getTransferData(candidate: DataFlavor): Any = value
    }
}
