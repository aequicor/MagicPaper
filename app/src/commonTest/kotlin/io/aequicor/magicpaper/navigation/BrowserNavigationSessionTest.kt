package io.aequicor.magicpaper.navigation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserNavigationSessionTest {
    private class Claims {
        val active = mutableSetOf<String>()
        suspend fun acquire(key: String): BrowserJournalLease? {
            if (!active.add(key)) return null
            return object : BrowserJournalLease { override fun close() { active.remove(key) } }
        }
    }

    @Test fun duplicateTabForksAKeyWhileReloadReusesTheReleasedDocumentKey() = runTest {
        val claims = Claims()
        val original = claimBrowserNavigationSession("original", null, null, claims::acquire)
        val duplicate = claimBrowserNavigationSession("original", "original", null, claims::acquire) { "duplicate" }
        assertEquals("duplicate", duplicate.journalKey)
        assertEquals("original", duplicate.restoreFromKey)
        assertEquals(setOf("original", "duplicate"), claims.active)
        original.close()
        val reloaded = claimBrowserNavigationSession("original", "duplicate", null, claims::acquire)
        assertEquals("original", reloaded.journalKey)
        assertNull(reloaded.restoreFromKey)
        duplicate.close(); reloaded.close()
        assertTrue(claims.active.isEmpty())
    }

    @Test fun ordinaryNewTabRestoresTheHintIntoItsOwnJournalAndPreservesTheIncomingPath() = runTest {
        val claims = Claims()
        val tab = claimBrowserNavigationSession(null, "last-tab", "/docs/guide", claims::acquire) { "fresh" }
        assertEquals("fresh", tab.journalKey)
        assertEquals("last-tab", tab.restoreFromKey)
        assertEquals("/docs/guide", tab.initialPath)
        tab.close()
    }

    @Test fun unavailableOwnershipFailsBeforeASecondWriterOrFallbackCanStart() = runTest {
        var attempts = 0
        assertFailsWith<IllegalStateException> {
            claimBrowserNavigationSession("original", null, null, acquire = {
                attempts++
                error("unavailable")
            })
        }
        assertEquals(1, attempts)
    }
}
