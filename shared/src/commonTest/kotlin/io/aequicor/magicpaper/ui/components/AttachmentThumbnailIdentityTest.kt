package io.aequicor.magicpaper.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AttachmentThumbnailIdentityTest {
    @Test
    fun contentIdentityDoesNotUseAHashThatCanCollide() {
        // Java/Kotlin's well-known collision proves that a String.hashCode cache key
        // could show an old image for new attachment data.
        val first = AttachmentThumbnailKey("same", "image/png", 2, "Aa")
        val second = AttachmentThumbnailKey("same", "image/png", 2, "BB")
        check("Aa".hashCode() == "BB".hashCode())

        assertNotEquals(first, second)
    }

    @Test fun sameNameAndIdStillKeepDifferentPayloadsSeparate() {
        val pngA = AttachmentThumbnailKey("shared-id", "image/png", 8, "payload-a")
        val pngB = AttachmentThumbnailKey("shared-id", "image/png", 8, "payload-b")

        assertNotEquals(pngA, pngB)
    }

    @Test fun unchangedPayloadKeepsItsStableCacheIdentity() {
        val attachment = AttachmentThumbnailKey("id", "image/png", 8, "payload")

        assertEquals(attachment, attachment.copy())
    }

    @Test fun cacheDoesNotRetainOversizedIdentityPayloads() {
        val oversized = AttachmentThumbnailKey("id", "image/png", 1, "x".repeat(MAX_CACHED_THUMBNAIL_KEY_CHARS + 1))

        assertEquals(false, thumbnailKeyCanBeCached(oversized))
    }

    @Test fun lateResultCannotReplaceNewPayloadOrAReleasedRequest() {
        val old = AttachmentThumbnailKey("same", "image/png", 3, "old")
        val current = AttachmentThumbnailKey("same", "image/png", 3, "new")
        val gate = AttachmentThumbnailRequestGate()
        val oldRequest = gate.begin(old)
        val currentRequest = gate.begin(current)

        assertEquals(false, gate.accepts(old, oldRequest), "old decode must not publish after data changes")
        assertEquals(true, gate.accepts(current, currentRequest))
        gate.clear(current, currentRequest)
        assertEquals(false, gate.accepts(current, currentRequest), "removed/session-disposed request must not publish")
    }
}
