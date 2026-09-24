package io.aequicor.magicpaper.domain

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class LlmTransportException(
    val statusCode: Int,
    val retryAfter: String?,
    detail: String,
    /** Машинные поля отказа из тела ошибки; null — провайдер не прислал разбираемый отказ. */
    val rejection: ProviderRejection? = null,
) : IllegalStateException("HTTP $statusCode: $detail" + retryAfter?.let { "\nRetry-After: $it" }.orEmpty()) {
    /**
     * Провайдер получил запрос и ответил отказом: ответа модели не существует, поэтому повтор
     * не удваивает внешний эффект. `408` и `5xx` означают потерянный ответ — их исход остаётся
     * неподтверждённым, как и в `HttpMediaGenerationGateway`.
     */
    val confirmedRejection: Boolean get() = statusCode in 400..499 && statusCode != 408

    /**
     * Автоматический повтор не поможет: причину меняет только человек — настройка подключения
     * или оплата. Так ведёт себя отказ в доступе к модели, который провайдеры присылают и
     * статусом 429 (Z.AI кодом 1113 сообщает им об отсутствии пакета ресурсов): повтор
     * дословно вернёт тот же отказ и только потратит бюджет попыток. Так же и с подпиской, в аккаунт
     * которой не выполнен вход: её исправляет только вход.
     */
    val blocksAutomaticRetry: Boolean get() = rejection?.refusal == ProviderRefusal.ENTITLEMENT ||
        rejection?.refusal == ProviderRefusal.SIGN_IN
}

/**
 * Отказ транспорта в цепочке причин. Владелец операции различает подтверждённый отказ провайдера
 * и потерю ответа по статусу, а не по разбору сообщений, которые содержат тело ответа провайдера.
 * Обход ограничен: петля причин не должна превращаться в вечный цикл.
 */
fun Throwable.transportRejection(): LlmTransportException? {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = this
    repeat(8) {
        val failure = current ?: return null
        if (failure is LlmTransportException) return failure
        if (!seen.add(failure)) return null
        current = try { failure.cause } catch (inspection: Throwable) { null }
    }
    return null
}

/** A rejected result needs another worker turn; unavailable verification only needs another check. */
fun StageAttempt.retryAfterUserAction(): StageAttempt = copy(
    verificationSnapshot = null,
    phase = if (error?.kind == IssueKind.VERIFICATION && phase == AttemptPhase.VERIFYING &&
        (acceptanceRecord == null || acceptanceRecord.canRetryWithWorker)) AttemptPhase.FAILED else phase,
    error = error?.copy(requiresUser = false),
)

/**
 * Whether a failure earns another automatic attempt, and when.
 *
 * Only a transient cause earns one — a dropped connection, a provider's 5xx, a rate limit.
 * Everything else ends at a person: repeating a missing model, an unsafe folder or an
 * unconfirmed effect is not recovery, it is the same failure at a slower rate.
 *
 * The attempt keeps three separate budgets — transport, repair and merge — so a flaky
 * network cannot spend the worker's chances to fix its own work. Only the transport budget
 * backs off in time; a repair waits for the scheduler's next pass and a merge conflict is
 * retried inside the merge itself.
 */
sealed interface RetryDecision {
    /** Another attempt follows on its own. [retries] is the new count, [notBefore] its earliest start. */
    data class Again(val retries: Int, val notBefore: Long) : RetryDecision

    /** The budget is spent. The count is kept so a person sees what was already tried. */
    data class Exhausted(val retries: Int) : RetryDecision

    /** Repetition cannot address this cause. The issue already says who is needed. */
    data object NotTransient : RetryDecision
}

/**
 * Records the decision on the issue the scheduler reads: it runs a stage again only while
 * `requiresUser` is false and `retryAt` has passed.
 */
fun RetryDecision.applyTo(issue: PlanningIssue): PlanningIssue = when (this) {
    is RetryDecision.Again -> issue.copy(retries = retries, retryAt = notBefore)
    is RetryDecision.Exhausted -> issue.copy(retries = retries, requiresUser = true)
    RetryDecision.NotTransient -> issue
}

object PlanningRetryPolicy {
    /**
     * The one place that decides whether a failure repeats itself.
     *
     * [now] and [jitter] are supplied rather than read, so the decision is reproducible: the
     * same failure with the same clock always yields the same answer. Jitter spreads the
     * retries of plans that failed together against the same provider.
     */
    fun decide(issue: PlanningIssue, completedRetries: Int, limit: Int?, now: Long, jitter: Long): RetryDecision = when {
        issue.kind != IssueKind.TRANSIENT -> RetryDecision.NotTransient
        !canRetry(completedRetries, limit) -> RetryDecision.Exhausted(completedRetries)
        else -> nextRetry(completedRetries).let {
            RetryDecision.Again(it, now + delayMillis(it, fromMessage(issue.message), now, jitter))
        }
    }

    /** Only recognizable local checks can resume without an external-effect acknowledgement. */
    fun localCheck(command: String): Boolean {
        val value = command.trim().lowercase()
        if (value.any { it in ";&|`<>\n\r" } || '$' in value) return false
        if (Regex("\\b(publish|deploy|upload|push|install|release)\\b").containsMatchIn(value)) return false
        return Regex("^(?:git (?:status|diff|log|show|rev-parse)\\b|(?:npm|pnpm|yarn) (?:test|run (?:test|lint|typecheck|build))\\b|(?:python(?:3)? -m )?pytest\\b|cargo (?:test|check|build)\\b|go test\\b|(?:\\./|\\.\\\\)?gradlew(?:\\.bat)? :?(?:(?:[\\w-]+:)*(?:[\\w-]*test|check|lint|assemble\\w*|compile\\w*|build))(?:\\s|$))").containsMatchIn(value)
    }
    /** Limits are user choices; absent limits never exhaust an automatic recovery path. */
    fun canRetry(completedRetries: Int, limit: Int?): Boolean = limit == null || completedRetries < limit
    fun nextRetry(completedRetries: Int): Int = if (completedRetries == Int.MAX_VALUE) completedRetries else completedRetries + 1

    /** A couple of immediate format corrections remain cheap; prolonged recovery backs off. */
    suspend fun awaitRetry(retry: Int) {
        currentCoroutineContext().ensureActive()
        if (retry > 2) delay(delayMillis(retry)) else yield()
    }

    fun delayMillis(retry: Int, retryAfter: String? = null, now: Long = 0, jitter: Long = 0): Long {
        require(retry >= 1)
        return maxOf(listOf(2000L, 5000L, 15000L)[(retry - 1).coerceAtMost(2)], retryAfterMillis(retryAfter, now)) + jitter.coerceIn(0, 500)
    }
    fun retryAfterMillis(value: String?, now: Long): Long {
        val raw = value?.trim() ?: return 0
        raw.toLongOrNull()?.let { return it.coerceIn(0, Long.MAX_VALUE / 1000) * 1000 }
        return runCatching {
            val parts = raw.substringAfter(",", raw).trim().split(Regex("\\s+"))
            val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            val clock = parts[3].split(":").map(String::toInt)
            (LocalDateTime(parts[2].toInt(), months.indexOf(parts[1]) + 1, parts[0].toInt(), clock[0], clock[1], clock[2])
                .toInstant(TimeZone.UTC).toEpochMilliseconds() - now).coerceAtLeast(0)
        }.getOrDefault(0)
    }
    fun fromMessage(message: String): String? = Regex("(?im)retry-after\\s*[:=]\\s*([^\\r\\n]+)").find(message)?.groupValues?.get(1)
}
