package io.aequicor.magicpaper.ui

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DocsComponentTest {
    @Test fun restoredSearchAndArticleIdentitySurviveAsynchronousLoading() = runTest {
        val loaded = CompletableDeferred<List<DocArticle>>()
        val repository = object : DocRepository {
            override suspend fun articles() = loaded.await()
            override suspend fun search(query: String, limit: Int) = emptyList<DocMatch>()
        }
        val lifecycle = LifecycleRegistry()
        val outputs = mutableListOf<DocsOutput>()
        val component = DefaultDocsComponent(DefaultComponentContext(lifecycle), repository,
            DocsInput(articleId = "second", query = "before"), outputs::add, StandardTestDispatcher(testScheduler))
        component.search("КЛЮЧ")
        loaded.complete(listOf(DocArticle("first", "Нет", "Описание"), DocArticle("second", "Статья", "Ключ поиска")))
        runCurrent()
        assertEquals(listOf("second"), component.state.value.articles.map { it.id })
        assertEquals("second", component.state.value.selectedArticle?.id)
        assertEquals(DocsOutput.QueryChanged("КЛЮЧ"), outputs.single())
        lifecycle.destroy()
    }
}
