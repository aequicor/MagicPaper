package io.aequicor.magicpaper.domain

/** Снимок одного окна лимитов подписки ChatGPT. */
data class OpenAiRateLimit(
    val id: String,
    val name: String,
    val window: String,
    val usedPercent: Int,
    val resetsAtEpochSeconds: Long? = null,
)

/** Аккаунт, которым desktop Codex app-server авторизован в ChatGPT. */
data class OpenAiSubscriptionAccount(
    val signedIn: Boolean,
    val email: String? = null,
    val planType: String? = null,
    val rateLimits: List<OpenAiRateLimit> = emptyList(),
    val rateLimitsUnavailable: Boolean = false,
)

data class OpenAiSubscriptionLogin(
    val id: String,
    val url: String,
)
