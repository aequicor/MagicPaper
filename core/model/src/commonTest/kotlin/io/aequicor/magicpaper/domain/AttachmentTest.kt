package io.aequicor.magicpaper.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Доменные правила вложений: тип по содержимому, лимиты, метаданные. */
class AttachmentTest {

    @Test
    fun imageByMime() {
        val att = Attachment.fromBytes("photo.png", "image/png", byteArrayOf(1, 2, 3))
        assertEquals(AttachmentKind.IMAGE, att.kind)
        assertTrue(att.visibleToChat)
    }

    @Test
    fun imageByExtensionWithoutMime() {
        val att = Attachment.fromBytes("скриншот.JPG", null, byteArrayOf(1))
        assertEquals(AttachmentKind.IMAGE, att.kind)
        assertEquals("image/jpeg", att.mimeType)
    }

    @Test
    fun textByExtension() {
        assertEquals(AttachmentKind.TEXT, Attachment.fromBytes("Main.kt", null, byteArrayOf(1)).kind)
        assertEquals(AttachmentKind.TEXT, Attachment.fromBytes("readme.md", null, byteArrayOf(1)).kind)
    }

    @Test
    fun binaryFallsBackToFile() {
        val att = Attachment.fromBytes("data.bin", "application/octet-stream", byteArrayOf(1))
        assertEquals(AttachmentKind.FILE, att.kind)
        assertTrue(!att.visibleToChat, "бинарь не виден модели в чате")
    }

    @Test
    fun chatVisibleFiltersBinary() {
        val attachments = listOf(
            Attachment.fromBytes("a.png", "image/png", byteArrayOf(1)),
            Attachment.fromBytes("b.txt", "text/plain", byteArrayOf(1)),
            Attachment.fromBytes("c.zip", "application/zip", byteArrayOf(1)),
        )
        assertEquals(2, attachments.chatVisible().size)
    }

    @Test
    fun metaCarriesDescriptionWithoutContent() {
        val att = Attachment.fromBytes("a.png", "image/png", byteArrayOf(1, 2, 3))
        val meta = att.asMeta(path = "/tmp/uploads/a.png")
        assertEquals(att.name, meta.name)
        assertEquals(att.sizeBytes, meta.sizeBytes)
        assertEquals("/tmp/uploads/a.png", meta.path)
    }

    @Test
    fun base64RoundTrip() {
        val source = byteArrayOf(0, 1, 2, 3, 127, -128, -1)
        val att = Attachment.fromBytes("x.bin", null, source)
        assertEquals(source.toList(), att.bytes.toList())
    }

    @Test
    fun sizeHumanReadable() {
        assertEquals("512 Б", formatSize(512))
        assertEquals("10 КБ", formatSize(10 * 1024))
        assertEquals("1.5 МБ", formatSize(1536 * 1024))
    }
}
