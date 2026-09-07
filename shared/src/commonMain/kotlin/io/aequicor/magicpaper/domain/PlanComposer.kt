package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.util.TextSimilarity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Планировщик: превращает цель в график мэилстоунов (связанные шаги с
 * зависимостями и параллельными ветвями) и закрепляет за каждого оптимального
 * агента с оптимальной моделью. Два пути (как у [SkillEducator]):
 *  1. модель подключена — модель разбивает цель на шаги;
 *  2. модели нет (или все попытки сорвались) — эвристический одношаговый план,
 *     честно помеченный причиной сбоя.
 * Имена агентов, предложенные моделью, сопоставляются с реальными профилями
 * по косинусной близости ([AgentMatcher]); несовпавшие получают лучшего из
 * свободных кандидатов. Без ответа планировщика compose пробует других
 * настроенных агентов — молчаливый откат в «один шаг» был главным источником
 * жалоб на планы.
 */
class PlanComposer(
    private val gateway: LlmGateway,
    private val json: Json = DEFAULT_JSON,
    private val searchEngine: SearchEngine? = null,
) {
    private val decisions = DecisionPlanner(gateway, json)

    suspend fun refine(
        plan: Plan, message: String, profile: LlmProfile?, candidates: List<LlmProfile>, dossiers: List<ModelDossier>,
        settings: AppSettings? = null, onActivity: (CodingStep) -> Unit = {}, onProgress: (String) -> Unit = {},
    ): Plan {
        require(profile?.configured == true) { "Выберите подключённую модель планировщика в настройках плана." }
        val context = if (settings != null && searchEngine != null) {
            onProgress("Поиск контекста для плана…")
            val effective = settings.copy(searchProvider = plan.searchProvider)
            require(effective.searchProvider != SearchProvider.GOOGLE ||
                (effective.googleApiKey.isNotBlank() && effective.googleSearchEngineId.isNotBlank())) {
                "Для Google заполните API-ключ и Search Engine ID в настройках."
            }
            require(effective.searchProvider != SearchProvider.QUERIT || effective.queritApiKey.isNotBlank()) {
                "Для Querit заполните API-ключ в настройках."
            }
            searchEngine.search(plan.goal, effective, 5).joinToString("\n\n") { "${it.title}\n${it.snippet}\n${it.url}" }
        } else ""
        onProgress(if (settings != null && context.isBlank()) "Источники не найдены. Планировщик готовит ответ…" else "Планировщик анализирует цель и готовит ответ…")
        return decisions.refine(plan, message, profile, candidates, dossiers, context, onActivity)
    }

    suspend fun generateAlternatives(plan: Plan, profile: LlmProfile?, candidates: List<LlmProfile>, dossiers: List<ModelDossier>) =
        refine(plan, "Разработай и оцени альтернативы по сохранённым уточнениям.", profile, candidates, dossiers)

    suspend fun recalculate(plan: Plan, nodeId: String, profile: LlmProfile?, candidates: List<LlmProfile>, dossiers: List<ModelDossier>, settings: AppSettings? = null, onActivity: (CodingStep) -> Unit = {}, onProgress: (String) -> Unit = {}): Plan {
        val nodes = plan.tree.associateBy { it.id }
        require(nodeId in nodes) { "Узел не найден" }
        val affected = mutableSetOf<String>()
        fun visit(id: String) { if (affected.add(id)) nodes[id]?.children?.forEach(::visit) }
        visit(nodeId)
        val stageIds = plan.tree.filter { it.id in affected && it.kind == DecisionKind.STAGE }.map { it.stageId ?: it.id }.toSet()
        val proposal = refine(plan, "Пересчитай участок «${nodes.getValue(nodeId).title}», сохрани остальные решения.", profile, candidates, dossiers, settings, onActivity, onProgress)
        val updated = proposal.copy(tree = plan.tree.filterNot { it.id in affected } + proposal.tree.filter { it.id in affected || it.id !in nodes },
            milestones = plan.milestones.filterNot { it.id in stageIds } + proposal.milestones.filter { it.id in stageIds || plan.milestones.none { old -> old.id == it.id } })
        DecisionCompiler.validateEdit(plan, updated)
        return updated
    }

    fun recommendChoices(plan: Plan) = decisions.recommendChoices(plan)

    @Serializable
    private data class RawMilestone(
        val title: String = "",
        val description: String = "",
        val agent: String = "",
        val model: String = "",
        /** Номера предшественников (с 1); оба написания, что любят модели. */
        val depends: List<Int> = emptyList(),
        @SerialName("depends_on") val dependsOn: List<Int> = emptyList(),
    ) {
        val dependsAll: List<Int> get() = depends + dependsOn
    }

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
        if (goal.isBlank()) return heuristicDraft(goal)
        // Порядок планировщиков: разрешённый judge, затем остальные настроенные —
        // сбой одного (таймаут, пустой ответ, неподдерживаемый транспорт) не должен
        // превращать план в молчаливую одну веху.
        val planners = buildList {
            profile?.takeIf { it.configured }?.let { add(it) }
            candidates.filter { it.configured }.forEach { p -> if (none { it.id == p.id }) add(p) }
        }
        if (planners.isEmpty()) return heuristicDraft(goal, "ни один источник не настроен")
        var lastError: String? = null
        for (planner in planners) {
            val draft = runCatching { modelDraft(goal, planner, dossiers, candidates) }
            draft.getOrNull()?.let { return it }
            lastError = draft.exceptionOrNull()?.message
        }
        return heuristicDraft(goal, lastError)
    }

    private suspend fun modelDraft(
        goal: String,
        profile: LlmProfile,
        dossiers: List<ModelDossier>,
        candidates: List<LlmProfile>,
    ): PlanDraft {
        val roster = buildString {
            appendLine("Доступные агенты (профили) и их сильные стороны;")
            appendLine("для шага можно указать оптимальную модель из списка моделей агента:")
            val usable = candidates.filter { it.configured }
            if (usable.isEmpty()) {
                appendLine("- (нет настроенных профилей)")
            }
            usable.forEach { p ->
                val dossier = dossiers.firstOrNull { it.profileId == p.id }
                val strengths = dossier?.strengths?.takeIf { it.isNotBlank() } ?: "описания нет"
                val rating = dossier?.rating?.takeIf { it > 0 }?.let { " · сила $it/5" } ?: ""
                val models = p.displayModels.joinToString(", ").ifBlank { "избранных моделей нет" }
                appendLine("- ${p.name}$rating: $strengths | модели: $models")
            }
        }
        val messages = listOf(
            LlmMessage(LlmChatRole.SYSTEM, COMPOSE_PROMPT),
            LlmMessage(LlmChatRole.SYSTEM, roster),
            LlmMessage(LlmChatRole.USER, "Цель задачи: $goal"),
        )
        val raw = gateway.complete(profile, messages)
        val steps = parseSteps(raw)
        require(steps.isNotEmpty()) { "модель вернула пустой план" }
        val bound = steps.mapIndexed { index, step ->
            MilestoneDraft(
                title = step.title.trim(),
                description = step.description.trim(),
                agent = resolveAgent(step.agent, dossiers, candidates),
                model = step.model.trim(),
                // Номера предшественников: только корректные (строго раньше текущего шага).
                depends = step.dependsAll.filter { it in 1..<index + 1 }.distinct(),
            )
        }
        // Одна веха от модели на объёмную цель — явный отказ: иначе «оптимальный
        // план» неотличим от молчаливого фолбэка, и дефект «всегда один шаг» не поймать.
        require(bound.size > 1 || goal.trim().length < 40) {
            "модель предложила один шаг на всю цель"
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

    /** Вырезаем JSON-массив шагов из ответа: перебираем сбалансированные скобки —
     * модель может обёрнуть его в ```-блок или обставить пояснениями. */
    private fun parseSteps(raw: String): List<RawMilestone> {
        val serializer = kotlinx.serialization.builtins.ListSerializer(RawMilestone.serializer())
        var start = raw.indexOf('[')
        while (start >= 0) {
            val end = matchingBracket(raw, start)
            if (end != null) {
                val steps = runCatching {
                    json.decodeFromString(serializer, raw.substring(start, end + 1))
                }.getOrNull()?.filter { it.title.isNotBlank() }
                if (!steps.isNullOrEmpty()) return steps
                start = end
            }
            start = raw.indexOf('[', start + 1)
        }
        return emptyList()
    }

    /** Парный `]` для `[` на позиции [start]; null — массив не закрыт. */
    private fun matchingBracket(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                inString && escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                inString && c == '"' -> inString = false
                !inString && c == '"' -> inString = true
                !inString && c == '[' -> depth++
                !inString && c == ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    /** Эвристический план: одна веха на всю цель, честно помечено — с причиной сбоя. */
    private fun heuristicDraft(goal: String, reason: String? = null): PlanDraft {
        val task = goal.trim().ifBlank { "выполнить задачу" }
        return PlanDraft(
            milestones = listOf(MilestoneDraft(title = task, description = "Выполни задачу целиком: $task")),
            note = buildString {
                append("План составлен без модели: один шаг на всю цель")
                if (!reason.isNullOrBlank()) append(" — сбой планировщика: $reason")
                append(". При рабочем источнике план разбивается на шаги с зависимостями.")
            },
        )
    }

    private companion object {
        val DEFAULT_JSON = Json { ignoreUnknownKeys = true }

        val COMPOSE_PROMPT = """
            Ты — планировщик инженерной задачи. Разбей цель на ГРАФИК из 2–6
            мэилстоунов: каждый — проверяемый результат (файл, работающая функция,
            тест). Никогда не отдавай весь план одним шагом, кроме тривиально
            коротких целей.
            Шаги, которые можно выполнять независимо, не связывай — они образуют
            параллельные ветви; зависимый шаг перечисляет номера предшественников
            в поле depends (нумерация шагов с 1).
            Для каждого шага выбери оптимальных агента и модель из списка
            доступных: того, чьи сильные стороны лучше всего подходят шагу.
            Ответь строго одним JSON-массивом без пояснений:
            [{"title": "короткое имя шага", "description": "что сделать и как
            проверить результат", "agent": "имя агента из списка или пустая
            строка", "model": "имя модели из списка моделей агента или пустая
            строка", "depends": [номера предшественников]}].
        """.trimIndent()
    }
}
