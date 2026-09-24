package io.aequicor.magicpaper.domain

/**
 * Desktop port to the user's installed Claude Code: requests of [ProviderType.ANTHROPIC_SUBSCRIPTION] are answered by
 * it and billed to the Claude subscription it is signed in to. The CLI owns the account and its credentials; the
 * application only asks whether it is signed in and starts its sign-in.
 */
interface ClaudeSubscriptionService : LlmGateway, ModelDirectory {
    /** null when Claude Code is missing or cannot tell. */
    suspend fun signedIn(): Boolean?
    /** Opens the CLI's sign-in page in the browser and returns once the flow ended. Cancellation stops it. */
    suspend fun signIn(): EngineSignInResult
}
