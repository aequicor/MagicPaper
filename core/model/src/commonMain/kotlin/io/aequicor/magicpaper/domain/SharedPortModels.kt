package io.aequicor.magicpaper.domain







data class DocArticle(val id: String, val title: String, val body: String)

data class DocMatch(val article: DocArticle, val score: Double)







data class SearchResult(val hits: List<SearchHit> = emptyList(), val issues: List<String> = emptyList())
