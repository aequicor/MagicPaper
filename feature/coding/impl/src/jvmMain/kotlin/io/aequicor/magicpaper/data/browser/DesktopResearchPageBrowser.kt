package io.aequicor.magicpaper.data.browser

import com.microsoft.playwright.*
import com.microsoft.playwright.options.WaitUntilState
import io.aequicor.magicpaper.data.researchPageProblem
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Uses the existing managed Chromium installation, in a visible isolated window.
 * The user owns all interactions; this adapter only navigates and reads on request. */
class DesktopResearchPageBrowser internal constructor(
    private val create: () -> Playwright,
    private val launch: (Playwright) -> Browser,
) : ResearchPageBrowser {
    constructor() : this({ Playwright.create() }, { it.chromium().launch(BrowserType.LaunchOptions().setHeadless(false)) })

    override suspend fun open(url: String): ResearchBrowserPage {
        require(researchUrl(url) != null)
        val page = VisiblePage(create, launch, url)
        try {
            page.open()
            currentCoroutineContext().ensureActive()
            return page
        } catch (error: Exception) {
            withContext(NonCancellable) {
                try { page.close() }
                catch (cleanup: Exception) {
                    AppLog.error("research.browser", "open.cleanup.failed", fields = mapOf("failure" to cleanup::class.simpleName.orEmpty()))
                    error.addSuppressed(cleanup)
                }
            }
            throw error
        }
    }

    private class VisiblePage(val create: () -> Playwright, val launch: (Playwright) -> Browser, val requested: String) : ResearchBrowserPage {
        private val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "magicpaper-source-browser").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        private val closed = AtomicBoolean()
        private var driver: Playwright? = null
        private var browser: Browser? = null
        private var context: BrowserContext? = null
        private var page: Page? = null
        private var openedUrl = requested
        private var status = 0

        suspend fun open() = withContext(dispatcher) {
            driver = create()
            browser = launch(checkNotNull(driver))
            context = checkNotNull(browser).newContext(Browser.NewContextOptions().setAcceptDownloads(false))
            page = checkNotNull(context).newPage().also { target ->
                target.setDefaultTimeout(10_000.0)
                target.onResponse { response ->
                    if (response.request().isNavigationRequest && response.frame() == target.mainFrame()) status = response.status()
                }
                try {
                    target.navigate(requested, Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(20_000.0))
                } catch (_: TimeoutError) {
                    // Leave the visible page open: its late load or challenge can be completed by the user.
                    AppLog.info("research.browser", "navigation.waiting", mapOf("reason" to "timeout", "recovery" to "user_read"))
                }
                openedUrl = target.url()
                target.bringToFront()
            }
        }

        override suspend fun read(): ResearchBrowserContent = withContext(dispatcher) {
            try { readPage() }
            catch (error: PlaywrightException) {
                if (page?.isClosed != false || browser?.isConnected != true)
                    throw ResearchBrowserUnavailable("Окно браузера закрыто. Откройте источник заново.", requiresReopen = true)
                throw error
            }
        }

        private fun readPage(): ResearchBrowserContent {
            val target = page?.takeUnless { it.isClosed }
                ?: throw ResearchBrowserUnavailable("Окно браузера закрыто. Откройте источник заново.", requiresReopen = true)
            fun normalized(url: String) = researchUrl(url)?.substringBefore('#')?.trimEnd('/')
            val current = normalized(target.url())
            if (current == null || current !in setOf(normalized(requested), normalized(openedUrl)))
                throw ResearchBrowserUnavailable("В браузере открыта другая страница. Вернитесь к исходному источнику.")
            if (status >= 400) throw ResearchBrowserUnavailable("Ошибка HTTP $status. Откройте доступный текст страницы и повторите чтение.")
            val content = evaluateBrowserScript(target, """() => {
                const root = document.querySelector('article, main, [role="main"]') || document.body;
                const text = (root?.innerText || '').slice(0, 24000);
                const escape = s => s.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
                const challenge = document.querySelector('[id="challenge-form"], [id="challenge-running"], [id="px-captcha"]');
                return {url: location.href, text, html: '<title>' + escape(document.title.slice(0, 1000)) + '</title><h1>' +
                    escape((document.querySelector('h1')?.innerText || '').slice(0, 1000)) + '</h1><p>' +
                    escape(text.slice(0, 1000)) + '</p>' + (challenge ? '<div id="challenge-form"></div>' : '')};
            }""")
            // CDP results use the same JSON conversion as the existing browser tools.
            if (content["truncated"] == JsonPrimitive(true)) throw ResearchBrowserUnavailable("Страница слишком большая для чтения. Добавьте материал файлом.")
            val result = Json.parseToJsonElement(content.getValue("result").jsonPrimitive.content).jsonObject
            val capturedUrl = result.getValue("url").jsonPrimitive.content
            if (normalized(capturedUrl) !in setOf(normalized(requested), normalized(openedUrl)))
                throw ResearchBrowserUnavailable("В браузере открыта другая страница. Вернитесь к исходному источнику.")
            if (status >= 400) throw ResearchBrowserUnavailable("Ошибка HTTP $status. Повторите чтение доступной страницы.")
            val text = result.getValue("text").jsonPrimitive.content
            val html = result.getValue("html").jsonPrimitive.content
            researchPageProblem(html, text)?.let { throw ResearchBrowserUnavailable(it) }
            return ResearchBrowserContent(capturedUrl, text)
        }

        override suspend fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                withContext(NonCancellable + dispatcher) {
                    try { context?.close() }
                    finally { try { browser?.close() } finally { driver?.close() } }
                }
            } finally { dispatcher.close() }
        }
    }
}
