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
        testBrowser(createPlaywright = { attempts++; error("private environment secret") }, availability = availability).use { browser ->
            assertTrue(browser.commands.isNotEmpty())
            repeat(3) { attempt ->
                val error = assertFailsWith<ToolStateRejection> { browser.execute("browser.open", args, "open-$attempt") }
                assertFalse(error.message.orEmpty().contains("secret"))
                assertContains(error.message.orEmpty(), "перезапустите MagicPaper")
            }
        }
        testBrowser(createPlaywright = { attempts++; error("must not start") }, availability = availability).use { browser ->
            assertTrue(browser.commands.isEmpty())
            assertFailsWith<ToolStateRejection> { browser.execute("browser.open", args, "next-session") }
        }
        assertEquals(1, attempts)
    }
}
