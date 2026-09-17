package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ResearchBrowserRecoveryTest {
    private val target = ResearchBrowserTarget("book", "question", ResearchResource("page", "Article", "https://example.org/article"))
    private class Page : ResearchBrowserPage {
        var text = "Verify you are human"
        var closed = false
        override suspend fun read(): ResearchBrowserContent {
            if (closed) throw ResearchBrowserUnavailable("Окно закрыто", requiresReopen = true)
            return ResearchBrowserContent("https://example.org/article", text)
        }
        override suspend fun close() { closed = true }
    }

    @Test fun challengeRequiresAnExplicitReadAndFailedReadCanBeRetried() = runTest {
        val page = Page()
        var state: ResearchBrowserState? = null
        val imports = mutableListOf<ResearchBrowserContent>()
        val recovery = ResearchBrowserRecovery(object : ResearchPageBrowser {
            override suspend fun open(url: String) = page
        }, backgroundScope, { state = it }, { _, content -> imports += content; true })
        recovery.open(target); runCurrent()
        assertEquals(ResearchBrowserPhase.READY, state?.phase)
        assertTrue(imports.isEmpty())
        recovery.read(); runCurrent()
        assertEquals(ResearchBrowserPhase.FAILED, state?.phase)
        assertContains(state?.problem.orEmpty(), "CAPTCHA")
        assertTrue(imports.isEmpty())
        page.text = "Article content after the user completed the check"
        recovery.read(); runCurrent()
        assertEquals(page.text, imports.single().text)
        assertNull(state)
        assertTrue(page.closed)
        recovery.close()
    }

    @Test fun replacingAnOpeningBrowserClosesItsLatePageAndCannotOverwriteTheNewTarget() = runTest {
        val gate = CompletableDeferred<Unit>()
        val first = Page(); val second = Page()
        var state: ResearchBrowserState? = null
        var opens = 0
        val recovery = ResearchBrowserRecovery(object : ResearchPageBrowser {
            override suspend fun open(url: String): ResearchBrowserPage {
                if (++opens == 1) withContext(NonCancellable) { gate.await() }
                return if (opens == 1) first else second
            }
        }, backgroundScope, { state = it }, { _, _ -> fail("Opening must not import") })
        recovery.open(target); runCurrent()
        val next = target.copy(questionId = "other", resource = target.resource.copy(id = "other", url = "https://other.example/page"))
        recovery.open(next); runCurrent(); gate.complete(Unit); runCurrent()
        assertTrue(first.closed)
        assertEquals("other", state?.questionId)
        assertEquals(ResearchBrowserPhase.READY, state?.phase)
        recovery.dismiss(); runCurrent()
        assertTrue(second.closed)
        assertNull(state)
        recovery.close()
    }

    @Test fun removedSourceCannotBeImportedAndClosedWindowCanBeReopened() = runTest {
        var page = Page()
        var state: ResearchBrowserState? = null
        val recovery = ResearchBrowserRecovery(object : ResearchPageBrowser {
            override suspend fun open(url: String) = Page().also { page = it }
        }, backgroundScope, { state = it }, { _, _ -> false })
        recovery.open(target); runCurrent()
        page.closed = true
        recovery.read(); runCurrent()
        assertFalse(state!!.pageOpen)
        recovery.read(); runCurrent()
        assertEquals(ResearchBrowserPhase.READY, state?.phase)
        page.text = "Readable article"
        recovery.read(); runCurrent()
        assertContains(state?.problem.orEmpty(), "удалён")
        recovery.close()
        assertTrue(page.closed)
    }

    @Test fun importedSnapshotsAreBoundedExpireAndNeverCrossNotebookBoundaries() {
        var now = 0L
        val content = ResearchBrowserSources { now }
        content.put(target, "readable article")
        assertEquals("readable article", content.apply("book", listOf(target.resource)).single().readableText)
        assertNull(content.apply("other", listOf(target.resource)).single().readableText)
        now = 30 * 60_000
        assertNull(content.apply("book", listOf(target.resource)).single().readableText)
        content.put(target, "text")
        repeat(30) { index -> content.put(target.copy(resource = target.resource.copy(url = "https://example.org/$index")), "text") }
        assertNull(content.apply("book", listOf(target.resource)).single().readableText)
        content.clear()
        assertNull(content.apply("book", listOf(target.resource.copy(url = "https://example.org/29"))).single().readableText)
    }

    @Test fun failedBrowserCleanupReportsAnActionableNotice() = runTest {
        var notice = ""
        val recovery = ResearchBrowserRecovery(object : ResearchPageBrowser {
            override suspend fun open(url: String) = object : ResearchBrowserPage {
                override suspend fun read() = ResearchBrowserContent(url, "Source text")
                override suspend fun close() { error("native cleanup failure") }
            }
        }, backgroundScope, {}, { _, _ -> true }, { notice = it })
        recovery.open(target); runCurrent()
        recovery.dismiss(); runCurrent()
        assertContains(notice, "Закройте его вручную")
        recovery.close()
    }
}
