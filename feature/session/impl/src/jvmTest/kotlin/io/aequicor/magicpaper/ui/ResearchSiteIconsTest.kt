package io.aequicor.magicpaper.ui

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.test.*

class ResearchSiteIconsTest {
    @Test fun pngAndIcoAreDecodedOnceAndCachedAtSmallDimensions() = runTest {
        for (bytes in listOf(png(64), ico(png(64)))) {
            var requests = 0
            val client = HttpClient(MockEngine { request ->
                requests++
                assertEquals("/favicon.ico", request.url.encodedPath)
                assertNull(request.headers[HttpHeaders.Authorization])
                respond(bytes)
            })
            try {
                val icons = ResearchSiteIcons(client)
                val first = assertNotNull(icons.load("https://example.org/favicon.ico"))
                assertEquals(32, first.width)
                assertEquals(32, first.height)
                assertSame(first, icons.load("https://example.org/favicon.ico"))
                assertEquals(1, requests)
            } finally { client.close() }
        }
    }

    @Test fun missingInvalidAndOversizedIconsUseCachedFallback() = runTest {
        val requests = mutableMapOf<String, Int>()
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            requests[path] = requests.getOrDefault(path, 0) + 1
            when (path) {
                "/missing" -> respond("", HttpStatusCode.NotFound)
                "/bytes" -> respond(ByteArray(256 * 1024 + 1))
                "/pixels" -> respond(png(513))
                else -> respond("<html>Not an icon</html>")
            }
        })
        try {
            val icons = ResearchSiteIcons(client)
            for (path in listOf("/missing", "/bytes", "/pixels", "/invalid")) {
                repeat(2) { assertNull(icons.load("https://example.org$path")) }
                assertEquals(1, requests[path], "Failed icons must not refetch on every row composition")
            }
        } finally { client.close() }
    }

    @Test fun cancellationPropagatesInsteadOfCachingAFailure() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine { requests++; throw CancellationException("Navigation") })
        try {
            val icons = ResearchSiteIcons(client)
            repeat(2) {
                assertFailsWith<CancellationException> { icons.load("https://example.org/favicon.ico") }
            }
            assertEquals(2, requests)
        } finally { client.close() }
    }

    private fun png(size: Int): ByteArray = ByteArrayOutputStream().use { out ->
        ImageIO.write(BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB), "png", out)
        out.toByteArray()
    }

    private fun ico(png: ByteArray): ByteArray = ByteBuffer.allocate(22 + png.size).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(0).putShort(1).putShort(1)
        .put(64.toByte()).put(64.toByte()).put(0.toByte()).put(0.toByte())
        .putShort(1).putShort(32).putInt(png.size).putInt(22).put(png).array()
}
