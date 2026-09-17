package io.aequicor.magicpaper.data.browser

import io.aequicor.magicpaper.domain.tools.ToolStateRejection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class BrowserAvailabilityTest {
    @Test fun failedStartupIsNotRetriedAndLaterSessionsDoNotAdvertiseTools() = runBlocking {
        val availability = BrowserAvailability()
        var attempts = 0
        val args = buildJsonObject { put("url", "https://example.org/") }
        BrowserToolSession(createPlaywright = { attempts++; error("private environment secret") }, availability = availability).use { browser ->
            assertTrue(browser.commands.isNotEmpty())
            repeat(3) {
                val error = assertFailsWith<ToolStateRejection> { browser.execute("browser.open", args) }
                assertFalse(error.message.orEmpty().contains("secret"))
                assertContains(error.message.orEmpty(), "перезапустите MagicPaper")
            }
        }
        BrowserToolSession(createPlaywright = { attempts++; error("must not start") }, availability = availability).use { browser ->
            assertTrue(browser.commands.isEmpty())
            assertFailsWith<ToolStateRejection> { browser.execute("browser.open", args) }
        }
        assertEquals(1, attempts)
    }
}
