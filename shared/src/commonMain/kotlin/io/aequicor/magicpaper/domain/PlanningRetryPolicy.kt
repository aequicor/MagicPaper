package io.aequicor.magicpaper.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class LlmTransportException(val statusCode: Int, val retryAfter: String?, detail: String) :
    IllegalStateException("HTTP $statusCode: $detail" + retryAfter?.let { "\nRetry-After: $it" }.orEmpty())

/** A rejected result needs another worker turn; unavailable verification only needs another check. */
internal fun StageAttempt.retryAfterUserAction(): StageAttempt = copy(
    verificationSnapshot = null,
    phase = if (error?.kind == IssueKind.VERIFICATION && phase == AttemptPhase.VERIFYING) AttemptPhase.FAILED else phase,
    error = error?.copy(requiresUser = false),
)

object PlanningRetryPolicy {
    /** Only recognizable local checks can resume without an external-effect acknowledgement. */
    fun localCheck(command: String): Boolean {
        val value = command.trim().lowercase()
        if (value.any { it in ";&|`<>\n\r" } || '$' in value) return false
        if (Regex("\\b(publish|deploy|upload|push|install|release)\\b").containsMatchIn(value)) return false
        return Regex("^(?:git (?:status|diff|log|show|rev-parse)\\b|(?:npm|pnpm|yarn) (?:test|run (?:test|lint|typecheck|build))\\b|(?:python(?:3)? -m )?pytest\\b|cargo (?:test|check|build)\\b|go test\\b|(?:\\./|\\.\\\\)?gradlew(?:\\.bat)? :?(?:(?:[\\w-]+:)*(?:[\\w-]*test|check|lint|assemble\\w*|compile\\w*|build))(?:\\s|$))").containsMatchIn(value)
    }
    fun delayMillis(retry: Int, retryAfter: String? = null, now: Long = 0, jitter: Long = 0): Long {
        require(retry in 1..3)
        return maxOf(listOf(2000L, 5000L, 15000L)[retry - 1], retryAfterMillis(retryAfter, now)) + jitter.coerceIn(0, 500)
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
