package io.aequicor.magicpaper.data.browser

import io.aequicor.magicpaper.data.tools.AgentToolBridge
import io.aequicor.magicpaper.data.tools.PiAgentToolExtension
import io.aequicor.magicpaper.data.tools.toolResultContent
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
        val host = ToolHost(receipts)
        var revoked = false
        host.checkScope = { check(!revoked) { "revoked" } }
        val session = host.session(ToolExecutionContext("p", "s", "s", "r", ToolRole.CHAT, CodingInteractionMode.CODE))
        val browser = BrowserToolSession(createPlaywright = { error("Validation must not launch a browser") })
        val args = buildJsonObject { put("html", VALID_HTML) }
        AgentToolBridge(session, browser = browser).use {
            assertTrue(session.definitions.any { it.id == "browser.open" })
            assertTrue("magicpaper_browser_validate_html" in PiAgentToolExtension.source(session))
            val result = session.call("validate", "magicpaper_browser_validate_html", args)
            assertTrue(result.jsonObject["validation"]!!.jsonObject["valid"]!!.jsonPrimitive.boolean)
            assertEquals(result, session.call("validate", "browser.validate_html", args))
            assertEquals(1, receipts.forRequest("p/s/r").size)
            revoked = true
            assertFailsWith<IllegalStateException> { session.call("denied", "browser.validate_html", args) }
        }
        assertTrue(session.definitions.none { it.id.startsWith("browser.") })
    }

    @Test fun auxiliaryRunsDoNotGainBrowserSideEffects() {
        val session = ToolHost(MemoryToolReceiptStore()).session(ToolExecutionContext("p", "s", "s", "r",
            ToolRole.CHAT, CodingInteractionMode.CODE, auxiliaryExecution = true))
        AgentToolBridge(session).use {
            assertTrue(session.definitions.none { it.id.startsWith("browser.") })
        }
    }

    @Test fun browserStartupFailureIsReportedWithoutRepeatedDriverLaunch() = runBlocking {
        val receipts = MemoryToolReceiptStore()
        val session = ToolHost(receipts).session(ToolExecutionContext("p", "s", "s", "r", ToolRole.CHAT, CodingInteractionMode.RESEARCH))
        var attempts = 0
        AgentToolBridge(session, browser = BrowserToolSession(createPlaywright = {
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

    @Test fun onlyScreenshotResultsCanEmitMcpImages() {
        val result = buildJsonObject {
            put("tabId", "tab-1")
            put("image", buildJsonObject { put("type", "image"); put("mimeType", "image/png"); put("data", "test") })
        }
        val blocks = toolResultContent("magicpaper_browser_screenshot", result)
        assertEquals(listOf("text", "image"), blocks.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertFalse("data" in blocks.first().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(1, toolResultContent("browser.evaluate", result).size)
    }
}

internal const val VALID_HTML = "<!doctype html><html lang='en'><head><meta charset='utf-8'><title>Fixture</title></head><body><h1>Fixture</h1></body></html>"
