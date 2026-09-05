package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Самообразование: превращает состоявшийся диалог в черновик навыка.
 * Два пути, как у зрелых агентских платформ:
 *  1. подключена модель — модель сама формулирует имя, назначение и инструкцию;
 *  2. модели нет или она ошиблась — эвристический черновик из последнего
 *     запроса пользователя (честно помечается «без модели»).
 * Черновик никогда не сохраняется сам: только после подтверждения пользователя.
 */
class SkillEducator(private val gateway: LlmGateway, private val json: Json = DEFAULT_JSON) {

    @Serializable
    private data class ModelDraft(val name: String = "", val description: String = "", val instructions: String = "")

    /** [profile] — разрешённый профиль подключения; null = модели нет. */
    suspend fun propose(history: List<ChatMessage>, profile: LlmProfile?): SkillDraft {
        val lastUser = history.lastOrNull { it.role == ChatRole.USER }?.text.orEmpty()
        if (profile == null || !profile.configured || history.isEmpty()) return heuristicDraft(lastUser)
        return runCatching { modelDraft(history, profile) }.getOrElse { heuristicDraft(lastUser) }
    }

    private suspend fun modelDraft(history: List<ChatMessage>, profile: LlmProfile): SkillDraft {
        val messages = buildList {
            add(LlmMessage(LlmChatRole.SYSTEM, PROPOSAL_PROMPT))
            history.takeLast(6).forEach { m ->
                add(LlmMessage(if (m.role == ChatRole.USER) LlmChatRole.USER else LlmChatRole.ASSISTANT, m.text))
            }
        }
        val raw = gateway.complete(profile, messages)
        val draft = parseDraft(raw)
        require(draft.name.isNotBlank() && draft.instructions.isNotBlank()) { "Модель вернула пустой черновик" }
        return draft
    }

    /** Вырезаем первый JSON-объект из ответа (модель может обернуть его в ```-блок). */
    private fun parseDraft(raw: String): SkillDraft {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "В ответе модели нет JSON" }
        val parsed = json.decodeFromString<ModelDraft>(raw.substring(start, end + 1))
        return SkillDraft(
            name = parsed.name.trim(),
            description = parsed.description.trim(),
            instructions = parsed.instructions.trim(),
        )
    }

    /** Эвристический черновик: инструкция просит повторить показанный подход. */
    private fun heuristicDraft(request: String): SkillDraft {
        val task = request.trim().ifBlank { "повторить показанную задачу" }
        val shortName = task.split(" ").take(6).joinToString(" ").replaceFirstChar { it.uppercase() }
        return SkillDraft(
            name = shortName,
            description = "Когда пользователя просят: $task",
            instructions = """
                Выполни задачу так же, как в прошлый раз: повтори подход, структуру и тон,
                которые понравились пользователю. Если деталей не хватает — задай один
                уточняющий вопрос, затем заверши задачу полностью.
            """.trimIndent(),
            note = "Создан без модели: подправьте инструкцию под себя.",
        )
    }

    private companion object {
        val DEFAULT_JSON = Json { ignoreUnknownKeys = true }

        val PROPOSAL_PROMPT = """
            Ты анализируешь диалог пользователя с ассистентом и превращаешь успешный подход
            в переиспользуемый навык. Ответь строго одним JSON-объектом без пояснений:
            {"name": "короткое имя (2-4 слова)", "description": "когда применять навык",
             "instructions": "пошаговая инструкция для ассистента, 2-6 предложений"}.
            Если в диалоге нет законченной задачи — верни пустые строки.
        """.trimIndent()
    }
}
