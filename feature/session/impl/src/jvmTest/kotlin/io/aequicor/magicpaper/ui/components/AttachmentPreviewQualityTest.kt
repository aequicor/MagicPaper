package io.aequicor.magicpaper.ui.components

import io.aequicor.magicpaper.domain.Attachment
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AttachmentPreviewQualityTest {
    @Test fun expandedPreviewRetainsSourceResolutionInsteadOfStretchingThumbnail() {
        val bytes = ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB), "png", output)
            output.toByteArray()
        }
        val attachment = Attachment.fromBytes("photo.png", "image/png", bytes)

        val thumbnail = assertNotNull(decodeAttachmentThumbnail(attachment))
        val preview = assertNotNull(decodeAttachmentPreview(attachment))

        assertTrue(maxOf(thumbnail.width, thumbnail.height) <= 96)
        assertEquals(800, preview.width)
        assertEquals(600, preview.height)
    }
}
