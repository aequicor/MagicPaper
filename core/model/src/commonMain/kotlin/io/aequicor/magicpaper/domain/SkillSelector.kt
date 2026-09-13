package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.util.TextSimilarity

/**
 * Подбор навыков под запрос. Селектор сравнивает запрос только с полем
 * «когда применять» (имя + описание + теги) — так же устроены скиллы
 * в агентских платформах: описание решает, грузится ли тело.
 */
class SkillSelector(private val threshold: Double = MIN_SCORE) {

    fun select(query: String, skills: List<Skill>, limit: Int = 3): List<Skill> {
        val qTokens = TextSimilarity.tokenize(query)
        if (qTokens.isEmpty()) return emptyList()
        return skills
            .filter { it.enabled }
            .map { skill ->
                skill to TextSimilarity.cosine(qTokens, TextSimilarity.tokenize(finderText(skill)))
            }
            .filter { it.second >= threshold }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    private fun finderText(skill: Skill): String =
        (listOf(skill.name, skill.description) + skill.tags).joinToString(" ")

    companion object {
        const val MIN_SCORE = 0.05
    }
}
