package io.aequicor.magicpaper.ui.screens

import io.aequicor.magicpaper.domain.Attachment
import io.aequicor.magicpaper.domain.ResearchResource
import kotlin.test.*

class ResearchSourcePresentationTest {
    @Test fun browserNavigationAcceptsOnlyValidatedHttpsAndPreservesArticleAnchors() {
        assertEquals("https://example.org:8443/article?lang=ru#methods",
            researchSourceBrowserUrl(" HTTPS://Example.org:8443/article?lang=ru#methods "))
        for (url in listOf("http://example.org/article", "javascript:alert(1)", "data:text/html,test", "file:///report.pdf",
            "//example.org", "https://", "https://user:secret@example.org", "https://example.org:99999",
            "https://example.org\\@other.org", "https://example.org/path\u0000hidden", "https://example.org/path\nnext",
            "https://example.org/has space", "not a URL")) {
            assertNull(researchSourceBrowserUrl(url), url)
            assertNull(ResearchResource("unsafe", "Источник", url).presentation().browserUrl, url)
        }
    }
    @Test fun errorsHaveShortLabelsWithoutLosingTheFullReason() {
        assertNull(researchSourceProblemLabel(null))
        assertEquals("HTTP 403", researchSourceProblemLabel("Ошибка HTTP 403"))
        assertEquals("Timeout", researchSourceProblemLabel("Истекло время ожидания страницы"))
        assertEquals("CAPTCHA", researchSourceProblemLabel("CAPTCHA или защита сайта"))
        assertEquals("Ошибка", researchSourceProblemLabel("Не удалось загрузить страницу"))
    }
    @Test fun websiteKeepsArticleTitleButOnlyShowsDomainAndRequestsOriginIcon() {
        val source = ResearchResource("site", "Название статьи",
            "https://WWW.Example.org:8443/articles/42?query=private#part").presentation()
        assertEquals("Название статьи", source.title)
        assertEquals("example.org:8443", source.detail)
        assertEquals("https://www.example.org:8443/favicon.ico", source.iconUrl)
        assertEquals("https://www.example.org:8443/articles/42?query=private#part", source.browserUrl)
        assertFalse(source.file)
    }

    @Test fun invalidOrCredentialUrlsNeverProduceIconRequests() {
        for (url in listOf("file:///report.pdf", "https://user:secret@example.org", "not a URL")) {
            val source = ResearchResource("site", "Ссылка", url).presentation()
            assertNull(source.iconUrl)
            assertNull(source.browserUrl)
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
