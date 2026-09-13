package io.aequicor.magicpaper.navigation

import io.aequicor.magicpaper.data.storage.StorageException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VisitPresentationEntriesTest {
    @Test fun legacyPlainQueryIsPreservedWhenAnotherOwnerSaves() {
        assertEquals("drafts", presentationEntry("drafts", "docs-query"))
        assertNull(presentationEntry("drafts", "compose"))
        val merged = withPresentationEntry("drafts", "compose", "scroll")
        assertEquals("drafts", presentationEntry(merged, "docs-query"))
        assertEquals("scroll", presentationEntry(merged, "compose"))
    }

    @Test fun corruptAndFutureEnvelopesCannotBeReinterpretedOrOverwritten() {
        listOf("{broken", "{}", "{\"version\":2,\"entries\":{}}").forEach { snapshot ->
            val readFailure = assertFailsWith<StorageException> { presentationEntry(snapshot, "docs-query") }
            assertEquals(StorageException.Kind.CORRUPT, readFailure.kind)
            assertNotNull(readFailure.cause)
            assertFailsWith<StorageException> { withPresentationEntry(snapshot, "compose", "replacement") }
        }
    }
}
