package io.aequicor.magicpaper.domain.tools

import kotlinx.coroutines.sync.Mutex

/** Both native transports and provider loops execute through this one receipt owner. */
class DefaultToolSessionFactory : ToolSession.Factory {
    override fun create(context: ToolExecutionContext, commands: List<ToolCommand<*, *>>, receipts: ToolReceiptStore,
        scope: ToolExecutionScope, authorization: ToolExecutionAuthorization, recovery: ToolExecutionRecovery,
        mediaReceiptLock: Mutex): ToolSession {
        val registry = ToolRegistry(commands)
        val executor = ToolExecutor(registry, receipts,
            checkScope = { scope.check(it) }, checkReplayScope = { scope.check(it, historical = true) },
            reconcile = recovery::reconcile, knownSecrets = scope::knownSecrets,
            recoverQuestionnaire = recovery::questionnaire, unknownOutcome = recovery::unknown,
            authorizeTool = authorization::authorizeTool, authorizeCommand = authorization::authorizeCommand,
            authorizeReceipt = authorization::authorizeReceipt, mediaReceiptLock = mediaReceiptLock)
        return DefaultToolSession(context, registry, executor, scope::knownSecrets)
    }
}
