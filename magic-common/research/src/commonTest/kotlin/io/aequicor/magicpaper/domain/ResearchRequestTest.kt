package io.aequicor.magicpaper.domain

import kotlin.test.*

class ResearchRequestTest {
    @Test fun sourceTransformationsNeverTriggerDiscoveryEvenForANewQuestion() {
        for (text in listOf("https://pubmed.ncbi.nlm.nih.gov/37396145/\nкраткий пересказ",
            "Перескажи статью", "Сделай выжимку из файла", "Переведи на русский", "Сократи предыдущий ответ",
            "Summarize the attached paper", "Translate this document", "Ответь только по выбранным источникам")) {
            val request = researchRequest(text)
            assertTrue(request.sourceTask, text)
            assertFalse(request.autoSearch(false, false, false), text)
        }
    }

    @Test fun explicitDiscoveryOverridesASummaryButNotANoSearchInstruction() {
        for (text in listOf("Поищи сведения", "Найди ещё источники", "Перескажи https://example.org и найди другие исследования")) {
            val request = researchRequest(text)
            assertTrue(request.asksForSearch, text)
            assertTrue(request.autoSearch(true, true, true), text)
        }
        assertTrue(researchRequest("Найди основные тезисы в https://example.org").sourceTask)
        assertTrue(researchRequest("Найди основные тезисы, без поиска в интернете").sourceTask)
    }

    @Test fun aTopicOnlyBootstrapsDiscoveryWithoutAnExistingConversationOrMaterial() {
        val request = researchRequest("Архитектура Android-приложения")
        assertTrue(request.autoSearch(false, false, false))
        assertFalse(request.autoSearch(true, false, false))
        assertFalse(request.autoSearch(false, true, false))
        assertFalse(request.autoSearch(false, false, true))
        assertFalse(researchRequest("2").autoSearch(true, true, false))
    }

    @Test fun explicitLinksAndFilesNarrowTheSourceSetAndNeverResurrectRemovedLinksOnResume() {
        val other = ResearchResource("other", "Unrelated", "https://example.org/other")
        val request = researchRequest("Краткий пересказ https://example.org/paper")
        assertEquals(listOf("https://example.org/paper"), request.sources(listOf(other), emptyList()).map { it.url })
        assertTrue(request.sources(listOf(other), emptyList(), includeNewLinks = false).isEmpty())
        val file = Attachment.fromBytes("paper.txt", "text/plain", "Body".encodeToByteArray())
        assertEquals(listOf(file), researchRequest("Перескажи файл").sources(listOf(other), listOf(file)).map { it.attachment })
    }
}
