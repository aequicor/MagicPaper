package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.logging.AppLog

import androidx.compose.runtime.*
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import io.aequicor.magicpaper.domain.DocArticle
import io.aequicor.magicpaper.domain.DocRepository
import io.aequicor.magicpaper.ui.screens.DocsScreen
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class DefaultDocsComponent(
    context: ComponentContext,
    private val repository: DocRepository,
    private val input: DocsInput,
    private val onOutput: (DocsOutput) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : DocsComponent {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(DocsState(query = input.query))
    override val state = mutableState.asStateFlow()
    private var allArticles = emptyList<DocArticle>()
    init {
        context.lifecycle.doOnDestroy { scope.cancel() }
        scope.launch {
            try {
                allArticles = repository.articles()
                update(mutableState.value.query)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { AppLog.error("docs-screen", "open.failed", failure); mutableState.value = mutableState.value.copy(loaded = true, error = "Не удалось загрузить документацию.") }
        }
    }
    override fun search(query: String) {
        update(query)
        onOutput(DocsOutput.QueryChanged(query))
    }
    private fun update(query: String) {
        val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        mutableState.value = DocsState(
            articles = allArticles.filter { article -> terms.all { article.title.contains(it, true) || article.body.contains(it, true) } },
            selectedArticle = allArticles.firstOrNull { it.id == input.articleId },
            query = query,
            loaded = true,
        )
    }
    @Composable override fun Content() {
        val current by state.collectAsState()
        DocsScreen(current, input.articleId, ::search,
            onArticle = { onOutput(DocsOutput.OpenArticle(it)) },
            onOverview = { onOutput(DocsOutput.Overview) })
    }
}
class DefaultDocsComponentFactory(private val repository: DocRepository) : DocsComponent.Factory {
    override fun create(context: ComponentContext, input: DocsInput, onOutput: (DocsOutput) -> Unit): DocsComponent =
        DefaultDocsComponent(context, repository, input, onOutput)
}
