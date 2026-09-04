package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.util.TextSimilarity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Планировщик: превращает цель в цепочку мэилстоунов и закрепляет за каждым
 * оптимального агента. Два пути (как у [SkillEducator]):
 *  1. модель подключена — модель разбивает цель на шаги;
 *  2. модели нет — эвристический одношаговый план, помеченный «без модели».
 * Имена агентов, предложенные моделью, сопоставляются с реальными профилями
 * по косинусной близости ([AgentMatcher]); несовпавшие получают лучшего из
 * свободных кандидатов.
 */
class PlanComposer(
    private val gateway: LlmGateway,
    private val json: Json = DEFAULT_JSON,
) {

    @Serializable
    private data class RawMilestone(val title: String = "", val description: String = "", val agent: String = "")

    /**
     * Составляет черновик плана. [dossiers] — досье моделей (подсказка для модели,
     * какие агенты есть и в чём они сильны), [candidates] — реальные профили.
     */
    suspend fun compose(
        goal: String,
        profile: LlmProfile?,
        dossiers: List<ModelDossier>,
        candidates: List<LlmProfile>,
    ): PlanDraft {
        if (profile == null || !profile.configured || goal.isBlank()) return heuristicDraft(goal)
        return runCatching { modelDraft(goal, profile, dossiers, candidates) }
            .getOrElse { heuristicDraft(goal) }
    }

    private suspend fun modelDraft(
        goal: String,
        profile: LlmProfile,
        dossiers: List<ModelDossier>,
        candidates: List<LlmProfile>,
    ): PlanDraft {
        val roster = buildString {
            appendLine("Доступные агенты (профили) и их сильные стороны:")
            val usable = candidates.filter { it.configured }
            if (usable.isEmpty()) {
                appendLine("- (нет настроенных профилей)")
            }
            usable.forEach { p ->
                val dossier = dossiers.firstOrNull { it.profileId == p.id }
                val strengths = dossier?.strengths?.takeIf { it.isNotBlank() } ?: "описания нет"
                val rating = dossier?.rating?.takeIf { it > 0 }?.let { " · сила $it/5" } ?: ""
                appendLine("- ${p.name} (${p.shortLabel}): $strengths$rating")
            }
        }
        val messages = listOf(
            LlmMessage("system", COMPOSE_PROMPT),
            LlmMessage("system", roster),
            LlmMessage("user", "Цель задачи: $goal"),
        )
        val raw = gateway.complete(profile, messages)
        val steps = parseSteps(raw)
        require(steps.isNotEmpty()) { "Модель вернула пустой план" }
        val bound = steps.map { step ->
            MilestoneDraft(
                title = step.title.trim(),
                description = step.description.trim(),
                agent = resolveAgent(step.agent, dossiers, candidates),
            )
        }
        return PlanDraft(milestones = bound)
    }

    /**
     * Сопоставляет имя агента из ответа модели с реальным профилем:
     * сначала точное вхождение имени, иначе косинусная близость.
     */
    private fun resolveAgent(agentName: String, dossiers: List<ModelDossier>, candidates: List<LlmProfile>): String {
        val usable = candidates.filter { it.configured }
        if (usable.isEmpty()) return ""
        val trimmed = agentName.trim()
        if (trimmed.isNotBlank()) {
            usable.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.let { return it.name }
            val best = usable.maxByOrNull { TextSimilarity.score(trimmed, "${it.name} ${it.shortLabel}") }
            if (best != null && TextSimilarity.score(trimmed, "${best.name} ${best.shortLabel}") > 0.2) return best.name
        }
        return ""
    }

    /** Вырезаем первый JSON-массив из ответа (модель может обернуть его в ```-блок). */
    private fun parseSteps(raw: String): List<RawMilestone> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        require(start >= 0 && end > start) { "В ответе модели нет JSON-массива" }
        return json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(RawMilestone.serializer()), raw.substring(start, end + 1))
            .filter { it.title.isNotBlank() }
    }

    /** Эвристический план: одна веха на всю цель, честно помечено. */
    private fun heuristicDraft(goal: String): PlanDraft {
        val task = goal.trim().ifBlank { "выполнить задачу" }
        return PlanDraft(
            milestones = listOf(MilestoneDraft(title = task, description = "Выполни задачу целиком: $task")),
            note = "План составлен без модели: один шаг на всю цель. При подключённом источнике план разбивается на шаги.",
        )
    }

    private companion object {
        val DEFAULT_JSON = Json { ignoreUnknownKeys = true }

        val COMPOSE_PROMPT = """
            Ты — планировщик инженерной задачи. Разбей цель на 2–6 последовательных
            мэилстоунов: каждый — проверяемый результат (файл, работающая функция, тест).
            Для каждого шага выбери оптимального агента из списка доступных: того, чьи
            сильные стороны лучше всего подходят шагу. Ответь строго одним JSON-массивом
            без пояснений: [{"title": "короткое имя шага", "description": "что сделать и
            как проверить результат", "agent": "имя агента из списка или пустая строка"}].
        """.trimIndent()
    }
}
