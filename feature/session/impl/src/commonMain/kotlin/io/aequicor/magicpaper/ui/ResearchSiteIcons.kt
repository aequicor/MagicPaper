package io.aequicor.magicpaper.ui

import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.util.Id
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Optional presentation assets. Fetch only the site's own favicon, never a third-party proxy.
 * Bounded bytes, pixels, concurrency and cache keep scrolling independent of network/decode work. */
class ResearchSiteIcons(private val client: HttpClient) {
    private data class Entry(val image: ImageBitmap?, val expires: Long)
    private val cache = linkedMapOf<String, Entry>()
    private val lock = Mutex()
    private val requests = Semaphore(4)

    suspend fun load(url: String): ImageBitmap? = withContext(Dispatchers.Default) {
        suspend fun cached() = lock.withLock { cache[url]?.takeIf { it.expires > Id.now() } }
        cached()?.let { return@withContext it.image }
        requests.withPermit {
            cached()?.let { return@withPermit it.image }
            val bitmap = try {
                withTimeoutOrNull(3_000) { fetch(url) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppLog.error("research.icons", "load.failed", fields = mapOf("causeType" to failure::class.simpleName.orEmpty()))
                null
            }
            if (bitmap == null) AppLog.debug("research.icons", "fallback", fields = mapOf("reason" to "icon_unavailable"))
            lock.withLock {
                cache[url] = Entry(bitmap, Id.now() + if (bitmap == null) 300_000 else 86_400_000)
                while (cache.size > 128) cache.remove(cache.keys.first())
            }
            bitmap
        }
    }

    private suspend fun fetch(url: String): ImageBitmap? = client.prepareGet(url).execute { response ->
        if (!response.status.isSuccess()) return@execute null
        val bytes = ByteArray(256 * 1024 + 1)
        var count = 0
        val channel = response.bodyAsChannel()
        while (count < bytes.size) {
            val read = channel.readAvailable(bytes, count, bytes.size - count)
            if (read < 0) break
            count += read
        }
        if (count == bytes.size) return@execute null
        decodeSiteIcon(bytes.copyOf(count))
    }
}

/** PNG and ICO are the standard favicon formats. Reject excessive dimensions before decoding. */
internal fun decodeSiteIcon(bytes: ByteArray): ImageBitmap? {
    fun u8(i: Int) = bytes.getOrNull(i)?.toInt()?.and(255) ?: 0
    fun le32(i: Int) = (0..3).fold(0L) { value, n -> value or (u8(i + n).toLong() shl (8 * n)) }
    fun png(offset: Int) = bytes.size >= offset + 24 && u8(offset) == 137 &&
        u8(offset + 1) == 80 && u8(offset + 2) == 78 && u8(offset + 3) == 71
    fun validPng(offset: Int): Boolean {
        fun be32(i: Int) = (0..3).fold(0L) { value, n -> (value shl 8) or u8(i + n).toLong() }
        return be32(offset + 16) in 1..512 && be32(offset + 20) in 1..512
    }
    val valid = when {
        png(0) -> validPng(0)
        bytes.size >= 22 && u8(0) == 0 && u8(1) == 0 && u8(2) == 1 && u8(3) == 0 -> {
            val entries = u8(4) + (u8(5) shl 8)
            entries in 1..32 && bytes.size >= 6 + entries * 16 && (0 until entries).all { index ->
                val entry = 6 + index * 16
                val length = le32(entry + 8)
                val offset = le32(entry + 12)
                length > 0 && offset >= 6 + entries * 16 && offset + length <= bytes.size &&
                    if (png(offset.toInt())) validPng(offset.toInt())
                    else length >= 40 && le32(offset.toInt()) >= 40 &&
                        le32(offset.toInt() + 4) in 1..512 && le32(offset.toInt() + 8) in 1..1024
            }
        }
        else -> false
    }
    if (!valid) return null
    val decoded = bytes.decodeToImageBitmap()
    val scale = minOf(1f, 32f / maxOf(decoded.width, decoded.height))
    if (scale == 1f) return decoded
    return ImageBitmap((decoded.width * scale).toInt().coerceAtLeast(1),
        (decoded.height * scale).toInt().coerceAtLeast(1)).also { small ->
        Canvas(small).drawImageRect(decoded, IntOffset.Zero, IntSize(decoded.width, decoded.height),
            IntOffset.Zero, IntSize(small.width, small.height), Paint())
    }
}
