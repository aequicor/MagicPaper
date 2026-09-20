package io.aequicor.magicpaper.data.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Playwright
import com.sun.net.httpserver.HttpServer
import io.aequicor.magicpaper.data.tools.AgentToolBridge
import io.aequicor.magicpaper.domain.CodingInteractionMode
import io.aequicor.magicpaper.domain.tools.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** No engine, account, user browser profile or external web service is used. */
class BrowserNativeTest {
    @Test fun evaluationDeadlineStopsLoopsAndUnresolvedPromises() {
        assumeTrue(java.lang.Boolean.getBoolean("magicpaper.browser.native"))
        Playwright.create(Playwright.CreateOptions().setEnv(mapOf("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1"))).use { driver ->
            driver.chromium().launch().use { browser ->
                val page = browser.newPage()
                page.setContent(VALID_HTML)
                assertFails { evaluateBrowserScript(page, "() => { while (true) {} }", timeoutMs = 200) }
                assertFails { evaluateBrowserScript(page, "() => new Promise(() => {})", timeoutMs = 200) }
                assertEquals("\"Fixture\"", evaluateBrowserScript(page, "() => document.title")["result"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test fun realBrowserNavigatesInteractsValidatesAndCloses() = runBlocking {
        assumeTrue(java.lang.Boolean.getBoolean("magicpaper.browser.native"))
        val submissions = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val source = VALID_HTML.replace("<h1>Fixture</h1>", """
            <h1>Browser fixture</h1><p>One<div>Two</div></p>
            <form action='/result'><label for='query'>Query</label><input id='query' name='q'><button>Search</button></form>
            <button type='button' onclick='this.textContent="Clicked"'>Click me</button>
            <a href='/result' target='_blank'>Popup</a>
            <script>throw new Error('fixture page error')</script>
        """.trimIndent())
        server.createContext("/") { exchange ->
            val body = if (exchange.requestURI.path == "/result") {
                submissions.incrementAndGet()
                VALID_HTML.replace("Fixture", "Result")
            } else source
            exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}/"
        var native: Browser? = null
        val browser = BrowserToolSession(launch = { it.chromium().launch().also { instance -> native = instance } },
            createPlaywright = { Playwright.create(Playwright.CreateOptions().setEnv(mapOf("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1"))) })
        val session = ToolHost(MemoryToolReceiptStore()).session(ToolExecutionContext("p", "s", "s", "r", ToolRole.CHAT, CodingInteractionMode.CODE))
        var call = 0
        suspend fun execute(tool: String, vararg fields: Pair<String, String>) = session.call("call-${++call}", "browser.$tool",
            buildJsonObject { fields.forEach { (key, value) -> put(key, value) } }).jsonObject
        try {
            AgentToolBridge(session, browser = browser).use {
                val opened = execute("open", "url" to url)
                val tab = opened["tabId"]!!.jsonPrimitive.content
                assertTrue("Browser fixture" in opened["snapshot"]!!.jsonPrimitive.content)
                assertTrue(opened["pageErrors"]!!.jsonArray.any { "fixture page error" in it.jsonPrimitive.content })
                assertFalse(execute("validate_html", "tabId" to tab)["validation"]!!.jsonObject["valid"]!!.jsonPrimitive.boolean)
                assertTrue(execute("validate_html", "tabId" to tab, "source" to "DOM")["validation"]!!.jsonObject["valid"]!!.jsonPrimitive.boolean)
                val clicked = execute("click", "tabId" to tab, "selector" to "text=Click me")
                assertTrue("Clicked" in clicked["snapshot"]!!.jsonPrimitive.content)
                execute("fill", "tabId" to tab, "selector" to "#query", "text" to "HTML & CSS")
                val value = execute("evaluate", "tabId" to tab, "script" to "() => document.querySelector('#query').value")
                assertEquals("\"HTML & CSS\"", value["result"]!!.jsonPrimitive.content)
                val shot = execute("screenshot", "tabId" to tab)
                val bytes = Base64.getDecoder().decode(shot["image"]!!.jsonObject["data"]!!.jsonPrimitive.content)
                assertContentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), bytes.take(8).toByteArray())
                val args = buildJsonObject { put("tabId", tab); put("selector", "#query"); put("key", "Enter") }
                val submitted = session.call("submit-once", "browser.press", args)
                assertEquals(submitted, session.call("submit-once", "magicpaper_browser_press", args))
                assertEquals(1, submissions.get())
                assertTrue("/result?q=HTML" in submitted.jsonObject["url"]!!.jsonPrimitive.content)
                execute("evaluate", "tabId" to tab, "script" to "() => { localStorage.setItem('scope', 'run-one'); return true; }")
            }
            assertFalse(native!!.isConnected)
            BrowserToolSession(createPlaywright = {
                Playwright.create(Playwright.CreateOptions().setEnv(mapOf("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1")))
            }).use { isolated ->
                val opened = isolated.execute("browser.open", buildJsonObject { put("url", url) }).jsonObject
                val value = isolated.execute("browser.evaluate", buildJsonObject {
                    put("tabId", opened["tabId"]!!); put("script", "() => localStorage.getItem('scope')")
                }).jsonObject
                assertEquals("null", value["result"]!!.jsonPrimitive.content)
            }
        } finally { browser.close(); server.stop(0) }
    }
}
