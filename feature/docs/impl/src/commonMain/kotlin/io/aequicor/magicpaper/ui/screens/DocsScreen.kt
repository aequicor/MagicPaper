package io.aequicor.magicpaper.ui.screens

import io.aequicor.magicpaper.designsystem.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.DocArticle
import io.aequicor.magicpaper.ui.DocsState

@Composable
fun DocsScreen(state: DocsState, articleId: String?, onQuery: (String) -> Unit, onArticle: (String) -> Unit, onOverview: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        PaperText("Документация", style = LocalPaperTypography.current.headline)
        Spacer(Modifier.height(8.dp))
        if (articleId == null) {
            PaperInput(value = state.query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth(),
                placeholder = { PaperText("Поиск по справочнику…") }, singleLine = true)
            Spacer(Modifier.height(12.dp))
        } else {
            PaperButton("Все статьи", onOverview, kind = PaperButtonKind.QUIET)
        }
        when {
            state.error != null -> PaperText(requireNotNull(state.error))
            !state.loaded -> PaperText("Загрузка…")
            articleId != null && state.selectedArticle == null -> PaperText("Статья не найдена")
            articleId != null -> LazyColumn { item(key = articleId) { DocCard(requireNotNull(state.selectedArticle)) } }
            state.articles.isEmpty() -> PaperText("Статьи не найдены")
            else -> LazyColumn { items(state.articles, key = { it.id }) { article ->
                Column {
                    PaperButton(article.title, { onArticle(article.id) }, kind = PaperButtonKind.QUIET)
                    DocCard(article, showTitle = false)
                }
            } }
        }
    }
}

@Composable
private fun DocCard(article: DocArticle, showTitle: Boolean = true) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (showTitle) PaperText(article.title, style = LocalPaperTypography.current.title)
        PaperText(article.body, style = LocalPaperTypography.current.body, color = LocalPaperColors.current.secondaryText)
        Spacer(Modifier.height(6.dp))
    }
}
