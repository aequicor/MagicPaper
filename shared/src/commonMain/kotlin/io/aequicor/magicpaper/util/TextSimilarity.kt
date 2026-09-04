package io.aequicor.magicpaper.util

import kotlin.math.sqrt

/**
 * Косинусная близость текстов по токенам (векторы частот слов).
 * Без внешних библиотек; единый примитив для поиска по документации,
 * каталогу навыков и подбора навыков агентом.
 */
object TextSimilarity {

    fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(' ', '\n', '\t', '.', ',', ';', ':', '!', '?', '(', ')', '-', '"', '\'', '/')
            .map { it.trim() }
            .filter { it.length > 1 }

    /** Косинусная близость двух списков токенов (0.0..1.0). */
    fun cosine(aTokens: List<String>, bTokens: List<String>): Double {
        if (aTokens.isEmpty() || bTokens.isEmpty()) return 0.0
        val a = vector(aTokens)
        val b = vector(bTokens)
        val dot = a.entries.sumOf { (k, v) -> v * (b[k] ?: 0.0) }
        val na = sqrt(a.values.sumOf { it * it })
        val nb = sqrt(b.values.sumOf { it * it })
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (na * nb)
    }

    /** Близость запроса к тексту. */
    fun score(query: String, text: String): Double = cosine(tokenize(query), tokenize(text))

    private fun vector(tokens: List<String>): Map<String, Double> {
        val counts = tokens.groupingBy { it }.eachCount()
        val total = counts.values.sum().toDouble().coerceAtLeast(1.0)
        return counts.mapValues { (_, c) -> c / total }
    }
}
