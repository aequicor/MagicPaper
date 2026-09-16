package io.aequicor.magicpaper.ui.screens

import io.aequicor.magicpaper.domain.Attachment
import io.aequicor.magicpaper.domain.ResearchResource
import kotlin.test.*

class ResearchSourcePresentationTest {
    @Test fun websiteKeepsArticleTitleButOnlyShowsDomainAndRequestsOriginIcon() {
        val source = ResearchResource("site", "Название статьи",
            "https://WWW.Example.org:8443/articles/42?query=private#part").presentation()
        assertEquals("Название статьи", source.title)
        assertEquals("example.org:8443", source.detail)
        assertEquals("https://www.example.org:8443/favicon.ico", source.iconUrl)
        assertFalse(source.file)
    }

    @Test fun invalidOrCredentialUrlsNeverProduceIconRequests() {
        for (url in listOf("file:///report.pdf", "https://user:secret@example.org", "not a URL")) {
            val source = ResearchResource("site", "Ссылка", url).presentation()
            assertNull(source.iconUrl)
            assertEquals("Сайт", source.detail)
        }
        assertEquals("example.org", ResearchResource("site", "", "https://example.org").presentation().title)
    }

    @Test fun fileUsesActualNameAndExtensionOrMimeWithoutReadingContents() {
        for ((name, mime, type) in listOf(
            Triple("Исследование.PdF", "application/octet-stream", "PDF"),
            Triple("Заметки.docx", "application/octet-stream", "DOCX"),
            Triple("Отчёт", "application/pdf", "PDF"),
            Triple("Данные", "application/octet-stream", "Файл"),
        )) {
            val attachment = Attachment.fromBytes(name, mime, byteArrayOf()).copy(dataBase64 = "intentionally invalid")
            val source = ResearchResource("file", "Другое название", attachment = attachment).presentation()
            assertEquals(name, source.title)
            assertEquals(type, source.detail)
            assertTrue(source.file)
            assertNull(source.iconUrl)
        }
    }
}
