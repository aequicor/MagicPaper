package io.aequicor.magicpaper.designsystem

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/** Validates encoded headers before allocating pixels. Call off the UI thread; the caller owns errors. */
public fun decodePaperMediaImage(bytes: ByteArray, mimeType: String): ImageBitmap =
    decodeRaster(bytes, mimeType, 12 * 1024 * 1024, 16_000_000L)

/** Small attachment previews retain the stricter composer budget and bounded output dimensions. */
public fun decodePaperThumbnailImage(bytes: ByteArray, mimeType: String): ImageBitmap {
    val decoded = decodeRaster(bytes, mimeType, 4 * 1024 * 1024, 4_000_000L)
    val scale = minOf(1f, 96f / maxOf(decoded.width, decoded.height))
    return if (scale == 1f) decoded else ImageBitmap(
        (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1),
    ).also { thumbnail ->
        Canvas(thumbnail).drawImageRect(decoded, IntOffset.Zero, IntSize(decoded.width, decoded.height),
            IntOffset.Zero, IntSize(thumbnail.width, thumbnail.height), Paint())
    }
}

private fun decodeRaster(bytes: ByteArray, mimeType: String, maxBytes: Int, maxPixels: Long): ImageBitmap {
    require(bytes.size in 1..maxBytes && isSupportedRasterImage(mimeType, bytes)) { "Invalid media image" }
    val dimensions = requireNotNull(imageDimensions(mimeType, bytes)) { "Invalid media dimensions" }
    require(dimensions.first > 0 && dimensions.second > 0 && dimensions.first.toLong() * dimensions.second <= maxPixels) {
        "Media image exceeds decode budget"
    }
    return bytes.decodeToImageBitmap()
}

private fun isSupportedRasterImage(mimeType: String, bytes: ByteArray): Boolean = when (mimeType.lowercase()) {
    "image/png" -> bytes.startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    "image/jpeg" -> bytes.startsWith(0xff, 0xd8, 0xff)
    "image/gif" -> bytes.startsWith('G'.code, 'I'.code, 'F'.code, '8'.code)
    "image/webp" -> bytes.startsWith('R'.code, 'I'.code, 'F'.code, 'F'.code) &&
        bytes.size >= 12 && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
        bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
    "image/bmp" -> bytes.startsWith('B'.code, 'M'.code)
    else -> false
}

private fun ByteArray.startsWith(vararg signature: Int): Boolean =
    size >= signature.size && signature.indices.all { this[it].toInt() and 0xff == signature[it] }

/** Read raster dimensions before decoding, preventing oversized images from reaching the decoder. */
private fun imageDimensions(mimeType: String, bytes: ByteArray): Pair<Int, Int>? = when (mimeType.lowercase()) {
    "image/png" -> bigEndianDimensions(bytes, 16)
    "image/gif" -> littleEndianDimensions(bytes, 6)
    "image/bmp" -> if (bytes.size >= 26) readLittleEndian32(bytes, 18) to kotlin.math.abs(readLittleEndian32(bytes, 22)) else null
    "image/jpeg" -> jpegDimensions(bytes)
    "image/webp" -> webpDimensions(bytes)
    else -> null
}

private fun bigEndianDimensions(bytes: ByteArray, offset: Int): Pair<Int, Int>? =
    if (bytes.size < offset + 8) null else readBigEndian(bytes, offset) to readBigEndian(bytes, offset + 4)

private fun littleEndianDimensions(bytes: ByteArray, offset: Int): Pair<Int, Int>? =
    if (bytes.size < offset + 4) null else readLittleEndian16(bytes, offset) to readLittleEndian16(bytes, offset + 2)

private fun jpegDimensions(bytes: ByteArray): Pair<Int, Int>? {
    var offset = 2
    while (offset + 9 < bytes.size) {
        while (offset < bytes.size && bytes[offset] != 0xff.toByte()) offset++
        while (offset < bytes.size && bytes[offset] == 0xff.toByte()) offset++
        if (offset >= bytes.size) return null
        val marker = bytes[offset++].toInt() and 0xff
        if (marker in setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)) {
            if (offset + 7 > bytes.size || readBigEndian16(bytes, offset) < 7) return null
            return readBigEndian16(bytes, offset + 5) to readBigEndian16(bytes, offset + 3)
        }
        if (offset + 1 >= bytes.size) return null
        val length = readBigEndian16(bytes, offset)
        if (length < 2) return null
        offset += length
    }
    return null
}

private fun webpDimensions(bytes: ByteArray): Pair<Int, Int>? = when {
    bytes.size >= 30 && bytes.copyOfRange(12, 16).decodeToString() == "VP8X" ->
        (1 + read24LittleEndian(bytes, 24)) to (1 + read24LittleEndian(bytes, 27))
    bytes.size >= 25 && bytes.copyOfRange(12, 16).decodeToString() == "VP8L" -> {
        val bits = (bytes[21].toInt() and 0xff) or ((bytes[22].toInt() and 0xff) shl 8) or
            ((bytes[23].toInt() and 0xff) shl 16) or ((bytes[24].toInt() and 0xff) shl 24)
        (1 + (bits and 0x3fff)) to (1 + ((bits shr 14) and 0x3fff))
    }
    bytes.size >= 30 && bytes.copyOfRange(12, 16).decodeToString() == "VP8 " &&
        bytes[23] == 0x9d.toByte() && bytes[24] == 0x01.toByte() && bytes[25] == 0x2a.toByte() ->
        (readLittleEndian16(bytes, 26) and 0x3fff) to (readLittleEndian16(bytes, 28) and 0x3fff)
    else -> null
}

private fun readBigEndian(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 24) or ((bytes[offset + 1].toInt() and 0xff) shl 16) or
        ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)

private fun readBigEndian16(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

private fun readLittleEndian16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

private fun read24LittleEndian(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8) or
        ((bytes[offset + 2].toInt() and 0xff) shl 16)

private fun readLittleEndian32(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8) or
        ((bytes[offset + 2].toInt() and 0xff) shl 16) or ((bytes[offset + 3].toInt() and 0xff) shl 24)
