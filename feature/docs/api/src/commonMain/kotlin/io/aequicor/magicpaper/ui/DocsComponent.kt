package io.aequicor.magicpaper.ui

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import io.aequicor.magicpaper.domain.DocArticle
import kotlinx.coroutines.flow.StateFlow

data class DocsInput(val articleId: String? = null, val query: String = "")
data class DocsState(
    val articles: List<DocArticle> = emptyList(),
    val selectedArticle: DocArticle? = null,
    val query: String = "",
    val loaded: Boolean = false,
    val error: String? = null,
)
sealed interface DocsOutput {
    data class OpenArticle(val id: String) : DocsOutput
    data class QueryChanged(val query: String) : DocsOutput
    data object Overview : DocsOutput
}
interface DocsComponent {
    val state: StateFlow<DocsState>
    fun search(query: String)
    @Composable fun Content()
    fun interface Factory {
        fun create(context: ComponentContext, input: DocsInput, onOutput: (DocsOutput) -> Unit): DocsComponent
    }
}
