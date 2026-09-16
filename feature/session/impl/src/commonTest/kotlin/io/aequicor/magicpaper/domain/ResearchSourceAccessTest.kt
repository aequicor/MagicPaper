package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.*

class ResearchSourceAccessTest {
    @Test fun readsAreBoundedAndFailuresAreRetriedOnNextRequest() = runTest {
        var active = 0
        var peak = 0
        var available = false
        val access = ResearchSourceAccess {
            active++; peak = maxOf(peak, active)
            try { delay(100); if (!available) error("transport error"); "Readable evidence" } finally { active-- }
        }
        val sources = (1..7).map { ResearchResource("$it", "Report", "https://example.org/$it", snippet = "not evidence") }
        assertTrue(access.check(sources).all { it.problem != null })
        assertTrue(peak <= 4)
        available = true
        val checked = access.check(sources).readableSources()
        assertEquals(7, checked.size)
        assertTrue(checked.all { it.readableText == "Readable evidence" && it.snippet.isEmpty() })
        val saved = Json.encodeToString(checked.first())
        assertFalse("Readable evidence" in saved)
        assertNull(Json.decodeFromString<ResearchResource>(saved).readableText)
    }

    @Test fun timeoutIsUnavailableButParentCancellationPropagates() = runTest {
        val source = ResearchResource("one", "Report", "https://example.org")
        val access = ResearchSourceAccess { awaitCancellation() }
        assertContains(access.check(source).problem.orEmpty(), "время ожидания")
        assertFailsWith<CancellationException> { ResearchSourceAccess { throw CancellationException() }.check(source) }
        assertNotNull(ResearchSourceAccess().check(source).problem)
    }
}
