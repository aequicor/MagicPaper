package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Вердикт проверки достижимости мэилстоуна. */
data class Verdict(val passed: Boolean, val note: String, val issue: PlanningIssue? = null)

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
 * недоступная проверка не принимает результат и не запускает исправление кода.
 */
class LlmMilestoneVerifier(
    private val gateway: LlmGateway,
    private val json: Json = DEFAULT_JSON,
) : MilestoneVerifier {

    @Serializable
    private data class RawVerdict(val passed: Boolean, val note: String)

    override suspend fun verify(
        milestone: Milestone,
        goal: String,
        report: String,
        profile: LlmProfile?,
    ): Verdict {
        if (report.isBlank()) return Verdict(false, "Пустой отчёт не подтверждает результат")
        if (profile == null || !profile.configured) {
            return Verdict(
                passed = false,
                note = "Без модели проверка не проводилась — просмотрите отчёт вручную.",
                issue = PlanningIssue(IssueKind.CONFIGURATION, "Подключите модель для проверки"),
            )
        }
        var failure = ""
        repeat(3) {
            try { return modelVerdict(milestone, goal, report, profile, failure) }
            catch (e: LlmTransportException) {
                val temporary = e.statusCode == 429 || e.statusCode >= 500
                return Verdict(false, e.message.orEmpty(), PlanningIssue(if (temporary) IssueKind.TRANSIENT else IssueKind.CONFIGURATION,
                    e.message.orEmpty(), requiresUser = !temporary && e.statusCode !in listOf(401, 403)))
            }
            catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                return Verdict(false, "Таймаут проверки", PlanningIssue(IssueKind.TRANSIENT, "Таймаут проверки"))
            }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { failure = e.message ?: failure }
        }
        return Verdict(false, "Проверка не сработала: $failure. Результат не принят.",
            PlanningIssue(IssueKind.INVALID_RESPONSE, failure, retries = 2, requiresUser = true))
    }

    private suspend fun modelVerdict(
        milestone: Milestone,
        goal: String,
        report: String,
        profile: LlmProfile,
        correction: String = "",
    ): Verdict {
        val messages = listOf(
            LlmMessage(LlmChatRole.SYSTEM, VERIFY_PROMPT),
            LlmMessage(
                LlmChatRole.USER,
                buildString {
                    appendLine("Общая цель задачи: $goal")
                    appendLine("Мэилстоун: ${milestone.title}")
                    appendLine("Критерий мэилстоуна: ${milestone.acceptance.ifBlank { milestone.description }.ifBlank { "(не задан)" }}")
                    appendLine("Отчёт агента о выполнении: ${report.ifBlank { "(пусто)" }}")
                    if (correction.isNotBlank()) appendLine("Исправь ошибку предыдущего ответа: $correction")
                },
            ),
        )
        val raw = gateway.complete(profile, messages)
        val verdict = parse(raw)
        require(verdict.note.isNotBlank()) { "Проверяющий не объяснил результат" }
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
            если критерий не требует точного артефакта.
            Учитывай историю отчётов одной попытки: краткое подтверждение в конце не
            отменяет прежние проверки само по себе. Новые сведения об ошибках,
            изменениях файлов или устаревших проверках имеют приоритет над прежним PASS.
            Прежний FAILED должен быть разрешён доказательствами. Слова «принято» и
            «этап завершён» от исполнителя или оркестратора не являются проверкой.
            NOT_RUN, SKIPPED и BLOCKED не превращай в PASS. Ответь одним
            JSON-объектом без пояснений: {"passed": true или false, "note":
            "одна-две фразы по-русски, почему"}.
        """.trimIndent()
    }
}
