package io.aequicor.magicpaper.data.skills

/** Derived from the live journal, never persisted as another copy of learning memory. */
data class ExperienceSuggestion(
    val scenario: ExperienceScenario,
    val features: Set<ExperienceFeature>,
    val sources: Set<String>,
    val confirmedCount: Int,
) {
    val explanation: String get() =
        "Подтверждённых успехов: $confirmedCount; порог: ${ExperienceSuggestions.MIN_SUCCESSES}. " +
            "Разные запуски с одинаковым сценарием и признаками; выбрано ${sources.size}. " +
            "Это основание для проверки шаблона, а не оценка его качества."
}

internal object ExperienceSuggestions {
    const val MIN_SUCCESSES = 3

    fun detect(outcomes: List<ExperienceOutcome>, candidates: List<ExperienceCandidate>): List<ExperienceSuggestion> {
        // All owned candidates consume evidence, including failed/interrupted evaluations.
        val used = candidates.flatMap { it.sources }.toSet()
        return outcomes.distinctBy { it.id }.filter {
            it.id !in used && it.scenario != null && it.success && it.result == ExperienceResult.SUCCESS &&
                it.verification in setOf(ExperienceVerification.PASSED, ExperienceVerification.USER_CONFIRMED)
        }.groupBy { requireNotNull(it.scenario) to it.features }.mapNotNull { (pattern, rows) ->
            if (rows.size < MIN_SUCCESSES) null else ExperienceSuggestion(
                pattern.first, pattern.second.toSet(),
                rows.sortedWith(compareBy<ExperienceOutcome> { it.time }.thenBy { it.id }).take(6).map { it.id }.toSet(),
                rows.size,
            )
        }.sortedWith(compareBy<ExperienceSuggestion> { it.scenario.name }.thenBy { it.features.map { f -> f.name }.sorted().joinToString() })
    }
}
