package io.aequicor.magicpaper.data.browser

import io.aequicor.magicpaper.domain.CodingInteractionMode
import io.aequicor.magicpaper.domain.tools.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.test.*

class BrowserToolsTest {
    @Test fun googleUsesAnEncodedWebPageAndUrlsCannotReadLocalFiles() {
        val query = "HTML & CSS / тест + #?"
        val uri = URI(googleSearchUrl(query))
        assertEquals("www.google.com", uri.host)
        assertEquals("/search", uri.path)
        assertEquals(query, URLDecoder.decode(uri.rawQuery.removePrefix("q="), StandardCharsets.UTF_8))
        for (url in listOf("file:///etc/passwd", "javascript:alert(1)", "data:text/html,test", "https://user:pass@example.org", "http://", "invalid"))
            assertFailsWith<ToolArgumentRejection>(url) { validateBrowserUrl(url) }
        validateBrowserUrl("http://localhost:8080/index.html")
        validateBrowserUrl("https://github.com/willyp713/awesome-ui-guides")
    }

    @Test fun commandsShareAuthorityReceiptsAndDetachWithoutLaunchingABrowser() = runBlocking {
        val receipts = MemoryToolReceiptStore()
        var revoked = false
        val session = testBrowserTools(receipts) { check(!revoked) { "revoked" } }
        val browser = testBrowser(createPlaywright = { error("Validation must not launch a browser") })
        val args = buildJsonObject { put("html", VALID_HTML) }
        attachBrowser(session, browser).use {
            assertTrue(session.definitions.any { it.id == "browser.open" })
            val result = session.call("validate", "magicpaper_browser_validate_html", args)
            assertTrue(result.jsonObject["validation"]!!.jsonObject["valid"]!!.jsonPrimitive.boolean)
            assertEquals(result, session.call("validate", "browser.validate_html", args))
            assertEquals(1, receipts.forRequest("p/s/r").size)
            revoked = true
            assertFailsWith<IllegalStateException> { session.call("denied", "browser.validate_html", args) }
        }
        assertTrue(session.definitions.none { it.id.startsWith("browser.") })
    }

    @Test fun browserStartupFailureIsReportedWithoutRepeatedDriverLaunch() = runBlocking {
        val receipts = MemoryToolReceiptStore()
        val session = testBrowserTools(receipts)
        var attempts = 0
        attachBrowser(session, testBrowser(createPlaywright = {
            attempts++; error("Sensitive provider body must not appear in the error")
        })).use {
            val args = buildJsonObject { put("url", "https://example.org") }
            repeat(2) { attempt ->
                val failure = assertFailsWith<ToolStateRejection> { session.call("attempt-$attempt", "browser.open", args) }
                assertFalse("Sensitive" in failure.message.orEmpty())
            }
            assertEquals(1, attempts)
            assertTrue(receipts.forRequest("p/s/r").all { receipt -> receipt.phase == ToolPhase.FAILED })
        }
    }


}

internal const val VALID_HTML = "<!doctype html><html lang='en'><head><meta charset='utf-8'><title>Fixture</title></head><body><h1>Fixture</h1></body></html>"
