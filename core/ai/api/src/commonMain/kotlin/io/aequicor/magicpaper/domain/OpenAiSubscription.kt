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
)

data class OpenAiSubscriptionLogin(
    val id: String,
    val url: String,
)

/**
 * Desktop-порт к официальному Codex app-server. Он управляет OAuth-токенами,
 * живым каталогом моделей и запросами, которые списываются с подписки ChatGPT.
 */
interface OpenAiSubscriptionService : LlmGateway, ModelDirectory {
    suspend fun account(refreshToken: Boolean = false): OpenAiSubscriptionAccount
    suspend fun startLogin(): OpenAiSubscriptionLogin
    suspend fun awaitLogin(loginId: String): OpenAiSubscriptionAccount
    suspend fun cancelLogin(loginId: String)
    suspend fun logout()
    fun close()
}
