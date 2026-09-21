package io.aequicor.magicpaper.data.browser

import com.microsoft.playwright.*
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.browser.BrowserMachine
import io.aequicor.magicpaper.domain.tools.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.lang.reflect.Proxy
import kotlin.test.*

class BrowserJournalTest {
    private val owner = BrowserMachine.Owner("s", "r")
    private val open = buildJsonObject { put("url", "https://example.org/private-query") }
    private val click = buildJsonObject { put("tabId", "tab-1"); put("selector", "private-selector") }

    @Test fun actionStartsOnlyAfterDurableIntentAndJournalExcludesPageAndInputBytes() = runBlocking {
        val backing = InMemoryEventJournal()
        val native = NativeBrowserFixture()
        native.onNavigate = {
            val entries = runBlocking { backing.read(backing.streams().single()) }
            assertEquals("Perform", Json.parseToJsonElement(entries.last().detail).jsonObject["input"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
        testBrowser(backing, createPlaywright = { native.driver }, launch = { native.browser }).use { browser ->
            browser.execute("browser.open", open, "open")
            browser.execute("browser.fill", buildJsonObject { put("tabId", "tab-1"); put("selector", "private-selector"); put("text", "private-password") }, "fill")
            val entries = backing.read(backing.streams().single()).joinToString { it.detail }
            for (secret in listOf("private-query", "private-selector", "private-password", VALID_HTML, "Private page title")) assertFalse(secret in entries)
            assertEquals(setOf("open", "fill"), browser.history.state.completed)
            assertEquals(1, native.navigations)
            assertEquals(1, native.fills)
        }
        assertEquals(listOf("context", "browser", "driver"), native.closed)
    }

    @Test fun exactLostAcknowledgementCommitsOnceAndRestorationLaunchesNothing() = runBlocking {
        val backing = InMemoryEventJournal()
        var loseAck = true
        val journal = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                val record = backing.append(expected, operation, at, detail)
                if (loseAck && "\"Perform\"" in detail) { loseAck = false; error("lost acknowledgement") }
                return record
            }
        }
        val native = NativeBrowserFixture()
        testBrowser(journal, createPlaywright = { native.driver }, launch = { native.browser }).use { browser ->
            browser.execute("browser.open", open, "open")
            assertEquals(1, native.navigations)
            assertFailsWith<ToolStateRejection> { browser.execute("browser.open", open, "open") }
        }
        testBrowser(journal, createPlaywright = { error("Replay must not create a native process") }).use { restored ->
            assertFailsWith<ToolStateRejection> { restored.execute("browser.open", open, "another") }
            assertEquals(BrowserMachine.Stage.CLOSED, restored.history.state.stage)
            assertTrue(restored.history.state.tabs.isEmpty())
        }
    }

    @Test fun failedIntentWriteAndCorruptAcknowledgementDoNotLaunchBrowser() = runBlocking {
        for (wrongAck in listOf(false, true)) {
            val backing = InMemoryEventJournal()
            var deny = true
            val journal = object : EventJournal by backing {
                override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                    if (deny && "\"Perform\"" in detail) {
                        if (wrongAck) return JournalRecord(expected.seq + 1, at, "another-stream", operation, detail)
                        error("storage unavailable")
                    }
                    return backing.append(expected, operation, at, detail)
                }
            }
            var launches = 0
            val browser = testBrowser(journal, createPlaywright = { launches++; error("must not launch") })
            assertFailsWith<BrowserJournalUnknown> { browser.execute("browser.open", open, "open") }
            deny = false
            assertFailsWith<BrowserJournalUnknown> { browser.execute("browser.open", open, "retry") }
            assertEquals(BrowserMachine.Stage.UNKNOWN, browser.history.state.stage)
            assertEquals(0, launches)
            assertFailsWith<IllegalStateException> { browser.close() }
        }
    }

    @Test fun mutationFollowedBySnapshotFailureStaysUnknownAfterInspectionAndRestart() = runBlocking {
        val backing = InMemoryEventJournal()
        val native = NativeBrowserFixture()
        val browser = testBrowser(backing, createPlaywright = { native.driver }, launch = { native.browser })
        browser.execute("browser.open", open, "open")
        native.snapshotFailure = true
        val failure = assertFailsWith<IllegalStateException> { browser.execute("browser.click", click, "click") }
        assertFalse("sensitive native detail" in failure.message.orEmpty())
        assertNotNull(failure.cause)
        assertEquals(1, native.clicks)
        assertEquals(setOf("click"), browser.history.state.unknown)
        native.snapshotFailure = false
        browser.execute("browser.snapshot", buildJsonObject { put("tabId", "tab-1") }, "inspect")
        assertEquals(BrowserMachine.Stage.UNKNOWN, browser.history.state.stage)
        assertFailsWith<ToolStateRejection> { browser.execute("browser.click", click, "repeat") }
        browser.close()
        testBrowser(backing, createPlaywright = { error("Restoration has no native effects") }).use { restored ->
            assertFailsWith<ToolStateRejection> { restored.execute("browser.click", click, "after-restart") }
            assertEquals(setOf("click"), restored.history.state.unknown)
        }
        assertEquals(1, native.clicks)
    }

    @Test fun resetFencesOldSessionAndForeignOwnerIsRejectedBeforeWriting() = runBlocking {
        val backing = InMemoryEventJournal()
        val native = NativeBrowserFixture()
        val browser = testBrowser(backing, createPlaywright = { native.driver }, launch = { native.browser })
        browser.execute("browser.open", open, "open")
        val command = browser.commands.single { it.definition.id == "browser.click" }
        assertFailsWith<ToolStateRejection> {
            command.execute(ToolExecutionContext("p", "foreign", "foreign", "r", ToolRole.CHAT,
                io.aequicor.magicpaper.domain.CodingInteractionMode.CODE), "foreign", click)
        }
        backing.drop(backing.streams().single())
        assertFailsWith<BrowserJournalUnknown> { browser.execute("browser.click", click, "late") }
        assertEquals(0, native.clicks)
        assertTrue(backing.streams().isEmpty())
        assertFailsWith<IllegalStateException> { browser.close() }
        assertEquals(listOf("context", "browser", "driver"), native.closed)
    }

    @Test fun missingTabsAreFactsAndCannotBeInventedByTheCaller() = runBlocking {
        testBrowser(createPlaywright = { error("Missing tab must not launch browser") }).use { browser ->
            assertFailsWith<ToolArgumentRejection> { browser.execute("browser.click", click, "missing") }
            assertEquals(BrowserMachine.Stage.READY, browser.history.state.stage)
            assertEquals(setOf("missing"), browser.history.state.completed)
        }
    }

    @Test fun corruptReplayIsRejectedForWrongStreamEpochOwnerOrderOrDuplicateEnvelope() = runBlocking {
        val backing = InMemoryEventJournal()
        val history = BrowserInputJournal(backing, owner)
        history.initialize()
        history.dispatch(BrowserMachine.Intent.Perform(BrowserMachine.Operation("pending", BrowserMachine.Action.CLICK, "a".repeat(64), "tab-1")))
        val saved = backing.snapshot(history.stream)
        val variants = listOf(
            saved.copy(revision = saved.revision.copy(stream = "foreign")),
            saved.copy(revision = saved.revision.copy(resetEpoch = 1)),
            saved.copy(records = saved.records.map { it.copy(stream = "foreign") }),
            saved.copy(records = saved.records.reversed()),
            saved.copy(records = saved.records.map { it.copy(detail = it.detail.replace("\"sessionId\":\"s\"", "\"sessionId\":\"foreign\"")) }),
            saved.copy(records = listOf(saved.records.first(), saved.records.first().copy(seq = saved.records.last().seq))),
        )
        for (invalid in variants) {
            val corrupted = object : EventJournal by backing { override suspend fun snapshot(stream: String) = invalid }
            val restored = BrowserInputJournal(corrupted, owner)
            assertFailsWith<BrowserJournalUnknown> { restored.initialize() }
            assertEquals(BrowserMachine.Stage.UNKNOWN, restored.state.stage)
        }
        val restored = BrowserInputJournal(backing, owner)
        restored.initialize()
        assertEquals(BrowserMachine.Stage.UNKNOWN, restored.state.stage)
        assertEquals(setOf("pending"), restored.state.unknown)
    }

    @Test fun cancellationAfterIntentCommitNeverStartsAnExternalEffect() = runBlocking {
        val backing = InMemoryEventJournal()
        val journal = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                val record = backing.append(expected, operation, at, detail)
                if ("\"Perform\"" in detail) throw CancellationException("caller cancelled")
                return record
            }
        }
        var launches = 0
        val browser = testBrowser(journal, createPlaywright = { launches++; error("cancelled call must not launch") })
        assertFailsWith<CancellationException> { browser.execute("browser.open", open, "cancelled") }
        assertEquals(0, launches)
        browser.close()
        val restored = BrowserInputJournal(backing, owner)
        restored.initialize()
        assertEquals(BrowserMachine.Stage.UNKNOWN, restored.state.stage)
    }

    @Test fun cleanupPreservesPrimaryFailureAndAttemptsEveryResource() = runBlocking {
        val native = NativeBrowserFixture()
        val browser = testBrowser(createPlaywright = { native.driver }, launch = { native.browser })
        browser.execute("browser.open", open, "open")
        native.closeFailure = true
        val failure = assertFailsWith<IllegalStateException> { browser.close() }
        assertEquals(listOf("context", "browser", "driver"), native.closed)
        assertEquals(2, failure.cause!!.suppressed.size)
        assertEquals(BrowserMachine.Stage.UNKNOWN, browser.history.state.stage)
    }

    @Test fun manualSourceWindowAlsoPreservesPrimaryCleanupFailure() = runBlocking {
        val native = NativeBrowserFixture()
        val window = DesktopResearchPageBrowser({ native.driver }, { native.browser }).open("https://example.org/private-query")
        native.closeFailure = true
        val failure = assertFailsWith<IllegalStateException> { window.close() }
        assertEquals("context cleanup failed", failure.message)
        // Coroutine stack recovery may wrap the original exception while preserving it as the cause.
        val primary = generateSequence(failure as Throwable) { it.cause }.firstOrNull { it.suppressed.isNotEmpty() }
        assertNotNull(primary)
        assertEquals(listOf("browser cleanup failed", "driver cleanup failed"), primary.suppressed.map { it.message })
        assertEquals(listOf("context", "browser", "driver"), native.closed)
    }
}

/** Strict interface proxies make native effects observable without starting a process or network. */
private class NativeBrowserFixture {
    var navigations = 0
    var clicks = 0
    var fills = 0
    var snapshotFailure = false
    var closeFailure = false
    var onNavigate: () -> Unit = {}
    val closed = mutableListOf<String>()
    private fun close(name: String) { closed += name; if (closeFailure) error("$name cleanup failed") }
    private val locator: Locator = proxy { name, _ -> when (name) {
        "click" -> { clicks++; null }
        "fill" -> { fills++; null }
        "ariaSnapshot" -> { if (snapshotFailure) throw PlaywrightException("sensitive native detail"); "page content" }
        else -> error("Unexpected locator call $name")
    } }
    private val page: Page = proxy { name, _ -> when (name) {
        "onPageError", "onResponse", "setDefaultTimeout", "bringToFront" -> null
        "isClosed" -> false
        "navigate" -> { onNavigate(); navigations++; null }
        "locator" -> locator
        "evaluate", "content" -> VALID_HTML
        "url" -> "https://example.org/private-query"
        "title" -> "Private page title"
        else -> error("Unexpected page call $name")
    } }
    private val context: BrowserContext = proxy { name, _ -> when (name) {
        "setDefaultTimeout", "setDefaultNavigationTimeout", "onPage" -> null
        "newPage" -> page
        "close" -> { close("context"); null }
        else -> error("Unexpected context call $name")
    } }
    val browser: Browser = proxy { name, _ -> when (name) {
        "newContext" -> context
        "close" -> { close("browser"); null }
        else -> error("Unexpected browser call $name")
    } }
    val driver: Playwright = proxy { name, _ -> when (name) {
        "close" -> { close("driver"); null }
        else -> error("Unexpected driver call $name")
    } }
    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> call(method.name, args ?: emptyArray()) } as T
}
