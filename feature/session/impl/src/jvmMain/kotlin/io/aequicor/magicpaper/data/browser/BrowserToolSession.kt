package io.aequicor.magicpaper.data.browser

import com.microsoft.playwright.*
import com.microsoft.playwright.options.WaitUntilState
import io.aequicor.magicpaper.domain.tools.*
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One bridge owns one isolated browser. All Playwright calls, including cleanup, use its owning thread. */
internal class BrowserToolSession(
    private val launch: (Playwright) -> Browser = { it.chromium().launch() },
    private val createPlaywright: () -> Playwright = { Playwright.create() },
    private val availability: BrowserAvailability = BrowserAvailability(),
) : AutoCloseable {
    private val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "magicpaper-browser").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val closed = AtomicBoolean()
    private var playwright: Playwright? = null
    private var browser: Browser? = null
    private var context: BrowserContext? = null
    private data class Tab(val page: Page, val errors: MutableList<String> = mutableListOf(), var response: Response? = null)
    private val tabs = linkedMapOf<String, Tab>()
    private val json = Json { encodeDefaults = true }
    private var nextTab = 0

    val commands get() = if (!availability.available) emptyList() else BrowserToolCatalog.definitions.map { definition ->
        JsonToolCommand(definition) { _, _, arguments -> execute(definition.id, arguments) }
    }

    private fun browserContext(): BrowserContext {
        context?.let { return it }
        val driver = playwright ?: availability.start("driver") { createPlaywright() }.also { playwright = it }
        val instance = browser ?: availability.start("chromium") { launch(driver) }.also { browser = it }
        return instance.newContext(Browser.NewContextOptions().setAcceptDownloads(false)).also { created ->
            created.setDefaultTimeout(15_000.0)
            created.setDefaultNavigationTimeout(30_000.0)
            created.onPage { page -> register(page) }
            context = created
        }
    }

    private fun register(page: Page): String {
        tabs.entries.firstOrNull { it.value.page === page }?.let { return it.key }
        val id = "tab-${++nextTab}"
        val tab = Tab(page)
        tabs[id] = tab
        page.onPageError { message ->
            if (tab.errors.size == 20) tab.errors.removeAt(0)
            tab.errors += message.take(2000)
        }
        page.onResponse { response ->
            if (response.request().isNavigationRequest && response.frame() == page.mainFrame()) tab.response = response
        }
        return id
    }

    private fun tab(id: String): Tab = tabs[id]?.takeUnless { it.page.isClosed }
        ?: throw ToolArgumentRejection("Вкладка недоступна. Откройте страницу заново через browser.open.")

    private fun navigate(url: String, tabId: String?): JsonObject {
        validateBrowserUrl(url)
        val id = tabId ?: register(browserContext().newPage())
        val target = tab(id)
        target.errors.clear()
        target.response = null
        target.page.navigate(url, Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED))
        return snapshot(id)
    }

    private fun snapshot(id: String): JsonObject {
        val target = tab(id)
        val aria = target.page.locator("body").ariaSnapshot()
        val html = target.page.evaluate("() => document.documentElement.outerHTML.slice(0, 32001)") as String
        return buildJsonObject {
            put("tabId", id); put("url", target.page.url()); put("title", target.page.title())
            put("snapshot", aria.take(32_000)); put("html", html.take(32_000))
            put("truncated", aria.length > 32_000 || html.length > 32_000)
            put("pageErrors", JsonArray(target.errors.map(::JsonPrimitive)))
            put("tabs", buildJsonArray { tabs.filterValues { !it.page.isClosed }.forEach { (key, value) ->
                add(buildJsonObject { put("tabId", key); put("url", value.page.url()) })
            } })
        }
    }

    internal suspend fun execute(tool: String, arguments: JsonObject): JsonElement = withContext(dispatcher) {
        if (closed.get()) throw ToolStateRejection("Запуск браузера завершён.")
        currentCoroutineContext().ensureActive()
        try {
            when (tool) {
                "browser.open" -> json.decodeFromJsonElement<BrowserOpen>(arguments).let { navigate(it.url, it.tabId) }
                "browser.search" -> json.decodeFromJsonElement<BrowserSearch>(arguments).let { navigate(googleSearchUrl(it.query), it.tabId) }
                "browser.snapshot" -> snapshot(json.decodeFromJsonElement<BrowserTab>(arguments).tabId)
                "browser.click" -> json.decodeFromJsonElement<BrowserClick>(arguments).let {
                    tab(it.tabId).page.locator(it.selector).click(); snapshot(it.tabId)
                }
                "browser.fill" -> json.decodeFromJsonElement<BrowserFill>(arguments).let {
                    tab(it.tabId).page.locator(it.selector).fill(it.text); snapshot(it.tabId)
                }
                "browser.press" -> json.decodeFromJsonElement<BrowserPress>(arguments).let {
                    tab(it.tabId).page.locator(it.selector).press(it.key); snapshot(it.tabId)
                }
                "browser.evaluate" -> json.decodeFromJsonElement<BrowserEvaluate>(arguments).let {
                    evaluateBrowserScript(tab(it.tabId).page, it.script)
                }
                "browser.screenshot" -> json.decodeFromJsonElement<BrowserScreenshot>(arguments).let {
                    if (it.width !in 320..1920 || it.height !in 320..1920) throw ToolArgumentRejection("Размер снимка должен быть от 320 до 1920 пикселей.")
                    if (it.format !in listOf("jpeg", "png")) throw ToolArgumentRejection("format: jpeg или png")
                    val page = tab(it.tabId).page
                    page.setViewportSize(it.width, it.height)
                    val options = Page.ScreenshotOptions().setType(if (it.format == "png")
                        com.microsoft.playwright.options.ScreenshotType.PNG else com.microsoft.playwright.options.ScreenshotType.JPEG)
                    if (it.format == "jpeg") options.setQuality(80)
                    buildJsonObject {
                        put("tabId", it.tabId); put("url", page.url())
                        put("image", buildJsonObject {
                            put("type", "image"); put("mimeType", "image/${it.format}")
                            put("data", Base64.getEncoder().encodeToString(page.screenshot(options)))
                        })
                    }
                }
                "browser.validate_html" -> json.decodeFromJsonElement<BrowserValidate>(arguments).let { args ->
                    if ((args.html == null) == (args.tabId == null)) throw ToolArgumentRejection("Укажите ровно одно: html или tabId.")
                    val source = args.html ?: tab(args.tabId!!).let { target ->
                        when (args.source) {
                            HtmlSource.DOM -> target.page.content()
                            HtmlSource.RESPONSE -> {
                                val response = target.response ?: throw ToolStateRejection("Исходный HTML недоступен. Передайте html или выберите source=DOM.")
                                if (!response.headers()["content-type"].orEmpty().contains("text/html", ignoreCase = true))
                                    throw ToolArgumentRejection("Ответ сервера не является HTML.")
                                response.text()
                            }
                        }
                    }
                    buildJsonObject {
                        put("source", if (args.html != null) "provided_html" else args.source.name)
                        put("validation", HtmlValidation.check(source))
                    }
                }
                else -> throw ToolArgumentRejection("Неизвестная операция браузера.")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PlaywrightException) {
            // Playwright exceptions can contain full URLs, field values and page scripts.
            AppLog.error("coding.browser", "operation.failed", mapOf("tool" to tool, "causeType" to error.javaClass.simpleName))
            throw IllegalStateException("Браузер не подтвердил выполнение. Проверьте страницу через browser.snapshot перед повтором действия.")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Run owners close on an I/O dispatcher. Joining prevents a late action from outliving its bridge.
        try {
            runBlocking(dispatcher) {
                try { context?.close() }
                finally { try { browser?.close() } finally { playwright?.close() } }
                tabs.clear()
            }
        } catch (error: Exception) {
            AppLog.error("coding.browser", "close.failed", mapOf("causeType" to error.javaClass.simpleName))
        } finally { dispatcher.close() }
    }
}

internal fun validateBrowserUrl(value: String) {
    val uri = try { URI(value) } catch (_: Exception) { throw ToolArgumentRejection("Укажите корректный HTTP(S)-адрес.") }
    if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null)
        throw ToolArgumentRejection("Нужен HTTP(S)-адрес без учётных данных. Для файлов используйте локальный сервер проекта.")
}

internal fun googleSearchUrl(query: String): String {
    if (query.isBlank() || query.length > 4000) throw ToolArgumentRejection("Поисковый запрос должен содержать от 1 до 4000 символов.")
    return "https://www.google.com/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
}
