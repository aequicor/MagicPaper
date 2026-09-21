package io.aequicor.magicpaper.data.browser

import com.microsoft.playwright.*
import com.microsoft.playwright.options.WaitUntilState
import io.aequicor.magicpaper.domain.tools.*
import io.aequicor.magicpaper.domain.browser.*
import io.aequicor.magicpaper.data.storage.EventJournal
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One bridge owns one isolated browser. All Playwright calls, including cleanup, use its owning thread. */
internal class BrowserToolSession(
    journal: EventJournal,
    private val owner: BrowserMachine.Owner,
    private val launch: (Playwright) -> Browser = { it.chromium().launch() },
    private val createPlaywright: () -> Playwright = { Playwright.create() },
    private val availability: BrowserAvailability = BrowserAvailability(),
) : BrowserSession {
    private val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "magicpaper-browser").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val closed = AtomicBoolean()
    private val operationLock = Mutex()
    internal val history = BrowserInputJournal(journal, owner)
    private var dispatched = false
    private var playwright: Playwright? = null
    private var browser: Browser? = null
    private var context: BrowserContext? = null
    private data class Tab(val page: Page, val errors: MutableList<String> = mutableListOf(), var response: Response? = null)
    private val tabs = linkedMapOf<String, Tab>()
    private val json = Json { encodeDefaults = true }
    private var nextTab = 0

    override val commands get() = if (!availability.available) emptyList() else BrowserToolCatalog.definitions.map { definition ->
        JsonToolCommand(definition) { context, operationId, arguments ->
            if (context.ownerSessionId != owner.sessionId || context.requestId != owner.requestId)
                throw ToolStateRejection("Браузерное действие принадлежит другому запуску.")
            execute(definition.id, arguments, operationId)
        }
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
        dispatched = true
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

    internal suspend fun execute(tool: String, arguments: JsonObject, operationId: String): JsonElement = withContext(dispatcher) {
        operationLock.withLock {
            if (closed.get()) throw ToolStateRejection("Запуск браузера завершён.")
            currentCoroutineContext().ensureActive()
            history.initialize()
            val operation = operation(tool, arguments, operationId)
            val transition = history.dispatch(BrowserMachine.Intent.Perform(operation))
            check(transition.effects.single() == BrowserMachine.Effect.Execute(operation))
            dispatched = false
            try {
                currentCoroutineContext().ensureActive()
                if (closed.get()) throw ToolStateRejection("Запуск браузера завершён.")
                if (operation.tabId != null && tabs[operation.tabId]?.page?.isClosed != false) {
                    history.dispatch(BrowserMachine.Fact.NeighbourMissing(operationId))
                    throw ToolArgumentRejection("Вкладка недоступна. Откройте страницу заново через browser.open.")
                }
                val result = perform(tool, arguments)
                withContext(NonCancellable) { history.dispatch(BrowserMachine.Fact.Completed(operationId,
                    tabs.filterValues { !it.page.isClosed }.keys.toSet())) }
                result
            } catch (failure: Exception) {
                if (history.state.pending?.id == operationId) {
                    try { withContext(NonCancellable) { history.dispatch(BrowserMachine.Fact.Failed(operationId, beforeEffect = !dispatched)) } }
                    catch (recordFailure: Exception) { failure.addSuppressed(recordFailure); history.persistenceUnknown(recordFailure) }
                }
                if (failure is CancellationException) throw failure
                if (failure is RejectedToolCall || failure is BrowserJournalUnknown) throw failure
                // Playwright errors may contain complete URLs, field values, or page scripts.
                AppLog.error("browser", "operation.failed", mapOf("tool" to tool, "operationId" to operationId,
                    "requestId" to owner.requestId, "causeType" to failure.javaClass.simpleName,
                    "result" to history.state.stage.name))
                throw IllegalStateException("Браузер не подтвердил выполнение. Проверьте страницу через browser.snapshot; автоматический повтор отключён.", failure)
            }
        }
    }

    private suspend fun operation(tool: String, arguments: JsonObject, id: String): BrowserMachine.Operation {
        val definition = BrowserToolCatalog.definitions.singleOrNull { it.id == tool }
            ?: throw ToolArgumentRejection("Неизвестная операция браузера.")
        validateToolArguments(definition.schema, arguments)
        when (tool) {
            "browser.open" -> validateBrowserUrl(json.decodeFromJsonElement<BrowserOpen>(arguments).url)
            "browser.search" -> googleSearchUrl(json.decodeFromJsonElement<BrowserSearch>(arguments).query)
            "browser.screenshot" -> json.decodeFromJsonElement<BrowserScreenshot>(arguments).let {
                if (it.width !in 320..1920 || it.height !in 320..1920) throw ToolArgumentRejection("Размер снимка должен быть от 320 до 1920 пикселей.")
                if (it.format !in listOf("jpeg", "png")) throw ToolArgumentRejection("format: jpeg или png")
            }
            "browser.validate_html" -> json.decodeFromJsonElement<BrowserValidate>(arguments).let {
                if ((it.html == null) == (it.tabId == null)) throw ToolArgumentRejection("Укажите ровно одно: html или tabId.")
            }
        }
        val action = when (tool) {
            "browser.validate_html" -> if (arguments["html"] != null) BrowserMachine.Action.VALIDATE_DOCUMENT else BrowserMachine.Action.VALIDATE_TAB
            else -> BrowserMachine.Action.valueOf(tool.removePrefix("browser.").uppercase())
        }
        return BrowserMachine.Operation(id, action, toolArgumentsFingerprint(arguments), arguments["tabId"]?.jsonPrimitive?.contentOrNull)
    }

    private fun perform(tool: String, arguments: JsonObject): JsonElement =
        when (tool) {
                "browser.open" -> json.decodeFromJsonElement<BrowserOpen>(arguments).let { navigate(it.url, it.tabId) }
                "browser.search" -> json.decodeFromJsonElement<BrowserSearch>(arguments).let { navigate(googleSearchUrl(it.query), it.tabId) }
                "browser.snapshot" -> snapshot(json.decodeFromJsonElement<BrowserTab>(arguments).tabId)
                "browser.click" -> json.decodeFromJsonElement<BrowserClick>(arguments).let {
                    dispatched = true
                    tab(it.tabId).page.locator(it.selector).click(); snapshot(it.tabId)
                }
                "browser.fill" -> json.decodeFromJsonElement<BrowserFill>(arguments).let {
                    dispatched = true
                    tab(it.tabId).page.locator(it.selector).fill(it.text); snapshot(it.tabId)
                }
                "browser.press" -> json.decodeFromJsonElement<BrowserPress>(arguments).let {
                    dispatched = true
                    tab(it.tabId).page.locator(it.selector).press(it.key); snapshot(it.tabId)
                }
                "browser.evaluate" -> json.decodeFromJsonElement<BrowserEvaluate>(arguments).let {
                    dispatched = true
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

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Join the operation owner; release every handle even when a journal/cleanup step fails.
        var primary: Throwable? = null
        fun failed(error: Throwable) {
            if (primary == null) primary = error else primary.addSuppressed(error)
        }
        try {
            runBlocking(dispatcher) {
                operationLock.withLock {
                    try { history.initialize(); history.dispatch(BrowserMachine.Intent.Close) } catch (error: Exception) { failed(error) }
                    for (release in listOf<() -> Unit>({ context?.close() }, { browser?.close() }, { playwright?.close() })) {
                        try { release() } catch (error: Exception) { failed(error) }
                    }
                    tabs.clear(); context = null; browser = null; playwright = null
                    if (primary == null) {
                        try { history.dispatch(BrowserMachine.Fact.Closed) } catch (error: Exception) { failed(error) }
                    }
                    primary?.let { history.persistenceUnknown(it) }
                }
            }
        } finally { dispatcher.close() }
        primary?.let {
            if (it is CancellationException) throw it
            throw IllegalStateException("Не удалось подтвердить закрытие браузера. Перезапустите приложение перед новым действием.", it)
        }
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
