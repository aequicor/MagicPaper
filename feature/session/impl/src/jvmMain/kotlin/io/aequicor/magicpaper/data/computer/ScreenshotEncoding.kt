package io.aequicor.magicpaper.data.computer

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.roundToInt

/** The source is already the requested region at native pixel density, never an enlarged overview. */
internal fun encodeDesktopCapture(source: java.awt.Image, resolution: ScreenshotResolution): DesktopCapture {
    val nativeWidth = source.getWidth(null); val nativeHeight = source.getHeight(null)
    require(nativeWidth > 0 && nativeHeight > 0 &&
        (resolution != ScreenshotResolution.NATIVE || nativeWidth.toLong() * nativeHeight <= MAX_NATIVE_SCREENSHOT_PIXELS)) {
        "Слишком большой снимок. Запросите screenshot с region для нужной области."
    }
    val scale = if (resolution == ScreenshotResolution.NATIVE) 1.0 else minOf(1.0, 1600.0 / maxOf(nativeWidth, nativeHeight))
    val width = (nativeWidth * scale).roundToInt().coerceAtLeast(1)
    val height = (nativeHeight * scale).roundToInt().coerceAtLeast(1)
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    try {
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally { graphics.dispose() }
        val png = ByteArrayOutputStream().use { output -> check(ImageIO.write(image, "png", output)); output.toByteArray() }
        return DesktopCapture(png, width, height)
    } finally { image.flush() }
}

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
