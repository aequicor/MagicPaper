package io.aequicor.magicpaper.domain

/**
 * Desktop port to the user's installed Claude Code: requests of [ProviderType.ANTHROPIC_SUBSCRIPTION] are answered by
 * it and billed to the Claude subscription it is signed in to. The CLI owns the account and its credentials; the
 * application asks for sign-in state and plan usage without reading the CLI's credentials.
 */
interface ClaudeSubscriptionService : LlmGateway, ModelDirectory {
    /** null when Claude Code is missing or cannot tell. */
    suspend fun signedIn(): Boolean?
    /** Opens the CLI's sign-in page in the browser and returns once the flow ended. Cancellation stops it. */
    suspend fun signIn(): EngineSignInResult
    /** Makes the CLI forget its login, so that the next [signIn] replaces a dead token. */
    suspend fun signOut(): EngineSignOutResult
    /** Current subscription allowances, if the installed CLI exposes them. Does not send a model prompt. */
    suspend fun planUsage(): PlanUsage? = null
}
