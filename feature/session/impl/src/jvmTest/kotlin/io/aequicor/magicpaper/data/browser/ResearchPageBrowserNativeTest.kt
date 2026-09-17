package io.aequicor.magicpaper.data.browser

import com.microsoft.playwright.*
import com.sun.net.httpserver.HttpServer
import io.aequicor.magicpaper.domain.ResearchBrowserUnavailable
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Local synthetic challenge only. No accounts, external sites or automated CAPTCHA solving. */
class ResearchPageBrowserNativeTest {
    @Test fun visibleBrowserReadsRenderedArticleAfterUserAssistanceAndRejectsOtherPages() = runBlocking {
        assumeTrue(java.lang.Boolean.getBoolean("magicpaper.browser.native"))
        val verified = AtomicBoolean()
        val leave = AtomicBoolean()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val (type, body) = when (exchange.requestURI.path) {
                "/status" -> "application/json" to """{"verified":${verified.get()},"leave":${leave.get()}}"""
                "/other" -> "text/html" to "<title>Other article</title><main>Different source</main>"
                else -> "text/html" to """<title>Verify you are human</title><main><h1>Verify you are human</h1></main>
                    <script>setInterval(async () => {
                        const state = await (await fetch('/status')).json();
                        if (state.leave) location.href='/other';
                        else if (state.verified) {document.title='Article'; document.querySelector('main').innerHTML='<h1>Article</h1><p>Verified article rendered by JavaScript.</p>';}
                    }, 100);</script>"""
            }
            exchange.responseHeaders.add("Content-Type", type)
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        var native: Browser? = null
        val adapter = DesktopResearchPageBrowser(
            { Playwright.create(Playwright.CreateOptions().setEnv(mapOf("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1"))) },
            { it.chromium().launch(BrowserType.LaunchOptions().setHeadless(false)).also { browser -> native = browser } })
        var page: io.aequicor.magicpaper.domain.ResearchBrowserPage? = null
        try {
            val opened = adapter.open("http://127.0.0.1:${server.address.port}/article").also { page = it }
            assertContains(assertFailsWith<ResearchBrowserUnavailable> { opened.read() }.reason, "CAPTCHA")
            verified.set(true)
            delay(600)
            assertContains(opened.read().text, "Verified article rendered by JavaScript.")
            leave.set(true)
            delay(600)
            assertContains(assertFailsWith<ResearchBrowserUnavailable> { opened.read() }.reason, "другая страница")
        } finally { page?.close(); server.stop(0) }
        assertFalse(native!!.isConnected)
    }
}
