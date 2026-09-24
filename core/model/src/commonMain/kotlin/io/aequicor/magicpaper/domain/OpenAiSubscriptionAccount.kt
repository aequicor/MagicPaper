package io.aequicor.magicpaper.domain

/** Снимок одного окна лимитов подписки ChatGPT. */
data class OpenAiRateLimit(
    val id: String,
    val name: String,
    val window: String,
    val usedPercent: Int,
    val resetsAtEpochSeconds: Long? = null,
    val durationMinutes: Long? = null,
) {
    /** The general Codex bucket covers the whole plan; any other metered bucket is named by its own limit. */
    fun planWindow() = PlanUsageWindow("$id:$window", usedPercent.coerceIn(0, 100) / 100f, durationMinutes,
        resetsAtEpochSeconds, name.takeIf { id != CODEX_LIMIT_ID })

    companion object { const val CODEX_LIMIT_ID = "codex" }
}

/** Аккаунт, которым desktop Codex app-server авторизован в ChatGPT. */
data class OpenAiSubscriptionAccount(
    val signedIn: Boolean,
    val email: String? = null,
    val planType: String? = null,
    val rateLimits: List<OpenAiRateLimit> = emptyList(),
    val rateLimitsUnavailable: Boolean = false,
    val limitReached: Boolean = false,
) {
    /** Null when there is nothing to show: signed out, or the limits could not be read. */
    fun planUsage(observedAt: Long): PlanUsage? = if (!signedIn || rateLimitsUnavailable) null
        else PlanUsage(ProviderType.OPENAI_SUBSCRIPTION, rateLimits.map { it.planWindow() }, planType, limitReached, observedAt)
}

data class OpenAiSubscriptionLogin(
    val id: String,
    val url: String,
)
