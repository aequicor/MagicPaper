package io.aequicor.magicpaper.domain

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
