package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.DocArticle
import io.aequicor.magicpaper.ui.MagicPaperViewModel

/** Экран документации с живым поиском по статьям. */
@Composable
fun DocsScreen(vm: MagicPaperViewModel, articles: List<DocArticle>, query: String) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Документация", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { vm.setDocsQuery(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Поиск по справочнику…") },
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
        Text(article.title, style = MaterialTheme.typography.titleMedium)
        Text(
            article.body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
    }
}
