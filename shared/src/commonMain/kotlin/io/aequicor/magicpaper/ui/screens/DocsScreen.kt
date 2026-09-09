package io.aequicor.magicpaper.ui.screens

import io.aequicor.magicpaper.designsystem.*

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.DocArticle
import io.aequicor.magicpaper.ui.MagicPaperViewModel

/** Экран документации с живым поиском по статьям. */
@Composable
fun DocsScreen(vm: MagicPaperViewModel, articles: List<DocArticle>, query: String) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        PaperText("Документация", style = LocalPaperTypography.current.headline)
        Spacer(Modifier.height(8.dp))
        PaperInput(
            value = query,
            onValueChange = { vm.setDocsQuery(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { PaperText("Поиск по справочнику…") },
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn {
            items(articles, key = { it.id }) { article ->
                DocCard(article)
            }
        }
    }
}

@Composable
private fun DocCard(article: DocArticle) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        PaperText(article.title, style = LocalPaperTypography.current.title)
        PaperText(
            article.body,
            style = LocalPaperTypography.current.body,
            color = LocalPaperColors.current.secondaryText,
        )
        Spacer(Modifier.height(6.dp))
    }
}
