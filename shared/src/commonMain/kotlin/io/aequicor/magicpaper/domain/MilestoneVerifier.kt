package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Вердикт проверки достижимости мэилстоуна. */
data class Verdict(val passed: Boolean, val note: String)

/**
 * Проверяющий достижимости: по отчёту агента и критерию мэилстоуна
 * выносит вердикт — достигнут ли проверяемый результат (порт для исполнителя).
 */
interface MilestoneVerifier {
    suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?): Verdict
}

/**
 * Проверка достижимости моделью: судья читает критерий и отчёт агента.
 * Без модели честно ставит прочерк и советует проверить руками;
 * сбой модели трактуется в пользу отчёта агента (с пометкой).
 */
class LlmMilestoneVerifier(
    private val gateway: LlmGateway,
    private val json: Json = DEFAULT_JSON,
) : MilestoneVerifier {

    @Serializable
    private data class RawVerdict(val passed: Boolean = false, val note: String = "")

    override suspend fun verify(
        milestone: Milestone,
        goal: String,
        report: String,
        profile: LlmProfile?,
    ): Verdict {
        if (profile == null || !profile.configured) {
            return Verdict(
                passed = report.isNotBlank(),
                note = "Без модели проверка не проводилась — просмотрите отчёт вручную.",
            )
        }
        return runCatching { modelVerdict(milestone, goal, report, profile) }
            .getOrElse { Verdict(passed = true, note = "Проверка не сработала (${it.message}) — шаг принят по отчёту агента.") }
    }

    private suspend fun modelVerdict(
        milestone: Milestone,
        goal: String,
        report: String,
        profile: LlmProfile,
    ): Verdict {
        val messages = listOf(
            LlmMessage(LlmChatRole.SYSTEM, VERIFY_PROMPT),
            LlmMessage(
                LlmChatRole.USER,
                buildString {
                    appendLine("Общая цель задачи: $goal")
                    appendLine("Мэилстоун: ${milestone.title}")
                    appendLine("Критерий мэилстоуна: ${milestone.description.ifBlank { "(не задан)" }}")
                    appendLine("Отчёт агента о выполнении: ${report.ifBlank { "(пусто)" }}")
                },
            ),
        )
        val raw = gateway.complete(profile, messages)
        val verdict = parse(raw)
        return Verdict(
            passed = verdict.passed,
            note = verdict.note.trim().ifBlank { if (verdict.passed) "Критерий достигнут." else "Критерий не достигнут." },
        )
    }

    /** Вырезаем первый JSON-объект из ответа модели. */
    private fun parse(raw: String): RawVerdict {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "В ответе модели нет JSON" }
        return json.decodeFromString(RawVerdict.serializer(), raw.substring(start, end + 1))
    }

    private companion object {
        val DEFAULT_JSON = Json { ignoreUnknownKeys = true }

        val VERIFY_PROMPT = """
            Ты проверяешь достижимость результата инженерной задачи. По описанию
            мэилстоуна (критерий) и отчёту агента реши, достигнут ли проверяемый
            результат. Будь строгим, но разумным: косвенные признаки допускаются,
            если критерий не требует точного артефакта. Ответь строго одним
            JSON-объектом без пояснений: {"passed": true или false, "note":
            "одна-две фразы по-русски, почему"}.
        """.trimIndent()
    }
}
