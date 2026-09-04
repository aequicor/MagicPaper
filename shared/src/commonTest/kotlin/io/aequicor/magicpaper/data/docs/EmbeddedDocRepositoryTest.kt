package io.aequicor.magicpaper.data.docs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbeddedDocRepositoryTest {

    private val repo = EmbeddedDocRepository()

    @Test
    fun findsProfileArticle() = kotlinx.coroutines.test.runTest {
        val matches = repo.search("как перенести профиль на другой компьютер")
        assertTrue(matches.isNotEmpty())
        assertEquals("profile", matches.first().article.id)
    }

    @Test
    fun findsSearchArticle() = kotlinx.coroutines.test.runTest {
        val matches = repo.search("поисковый движок querit")
        assertTrue(matches.isNotEmpty())
        assertEquals("search", matches.first().article.id)
    }

    @Test
    fun emptyQueryReturnsNothing() = kotlinx.coroutines.test.runTest {
        assertTrue(repo.search("   ").isEmpty())
    }
}
