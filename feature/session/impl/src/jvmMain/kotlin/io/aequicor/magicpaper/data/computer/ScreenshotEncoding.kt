package io.aequicor.magicpaper.data.computer

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

internal data class EncodedScreenshot(val bytes: ByteArray, val mimeType: String, val extension: String)

internal fun encodeScreenshot(png: ByteArray, format: String): EncodedScreenshot {
    require(format == "jpeg" || format == "png") { "format: jpeg или png" }
    if (format == "png") return EncodedScreenshot(png, "image/png", "png")
    val original = checkNotNull(ImageIO.read(png.inputStream())) { "Не удалось прочитать снимок экрана" }
    val rgb = BufferedImage(original.width, original.height, BufferedImage.TYPE_INT_RGB)
    val graphics = rgb.createGraphics()
    try { graphics.drawImage(original, 0, 0, null) } finally { graphics.dispose(); original.flush() }
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    try {
        val bytes = ByteArrayOutputStream().use { output ->
            ImageIO.createImageOutputStream(output).use { stream ->
                writer.output = stream
                val params = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = 0.8f
                }
                writer.write(null, javax.imageio.IIOImage(rgb, null, null), params)
            }
            output.toByteArray()
        }
        return EncodedScreenshot(bytes, "image/jpeg", "jpg")
    } finally { writer.dispose(); rgb.flush() }
}
