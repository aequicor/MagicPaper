package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.backend.NativeAgentAdapter
import io.aequicor.magicpaper.backend.NativeCompletionFailure
import io.aequicor.magicpaper.backend.NativeCompletionRequest
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.currentCoroutineContext

/**
 * [ProviderType.ANTHROPIC_SUBSCRIPTION] through the installed Claude Code, billed to the user's Claude subscription.
 *
 * The application's tools are not offered: the CLI runs the tools it is given itself and cannot hand a call back to the
 * caller's loop between turns, so a turn is one plain answer. Claude's own web search and fetch stay available to it.
 */
class ClaudeCodeSubscription(private val agent: NativeAgentAdapter) : ClaudeSubscriptionService {
    private val completion = checkNotNull(agent.completion?.takeIf { it.provider == ProviderType.ANTHROPIC_SUBSCRIPTION }) {
        "The engine does not answer requests of the Claude subscription"
    }

    override suspend fun signedIn(): Boolean? = agent.status().signedIn
    override suspend fun signIn(): EngineSignInResult =
        agent.signIn?.signIn() ?: EngineSignInResult.Failed("Вход для этого движка недоступен.")

    override suspend fun models(profile: LlmProfile): List<ModelDefaults.DiscoveredModel> {
        require(profile.provider == ProviderType.ANTHROPIC_SUBSCRIPTION)
        val catalog = checkNotNull(agent.models) { "Claude Code does not declare its models" }.models()
        // A plain answer runs without the Workflow tool, so the catalog's ultracode here is only its xhigh effort.
        val declared = catalog.associate { model ->
            val efforts = model.levels.mapNotNull(ReasoningEffort::fromWire).toSet()
            model.id to if (efforts.isEmpty()) DeclaredReasoning.None
                else DeclaredReasoning(efforts = efforts, mandatory = true, default = model.defaultLevel?.let(ReasoningEffort::fromWire))
        }
        val metadata = catalog.associate { model ->
            model.id to ProviderModel(model.id, model.name, model.contextWindow, model.maxTokens, supportedParameters = emptySet(),
                reasoning = declared[model.id])
        }
        return ModelDefaults.discover(ProviderType.ANTHROPIC_SUBSCRIPTION, catalog.map { it.id }, declared)
            .map { it.copy(metadata = metadata[it.id]) }
    }

    override suspend fun turn(profile: LlmProfile, messages: List<LlmMessage>, tools: List<LlmToolDefinition>,
        exchanges: List<LlmToolExchange>): LlmToolTurn {
        // This transport never returns a call, so a continuation can only come from another provider's turn.
        if (exchanges.isNotEmpty()) throw LlmToolProtocolException("tool results for a transport without tool calls")
        return LlmToolTurn(text = complete(profile, messages), provider = profile.provider)
    }

    override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = completeWithActivity(profile, messages) {}

    override suspend fun completeWithActivity(profile: LlmProfile, messages: List<LlmMessage>, onActivity: (CodingStep) -> Unit): String {
        require(profile.provider == ProviderType.ANTHROPIC_SUBSCRIPTION) { "Этот транспорт принимает только подписку Claude Code." }
        val system = messages.filter { it.role == LlmChatRole.SYSTEM }.joinToString("\n\n") { it.content }
            .ifBlank { profile.advanced.systemPromptOverride }
        val usage = currentCoroutineContext()[UsageCall]
        return try {
            completion.complete(NativeCompletionRequest(profile.modelId, system, CHAT_INSTRUCTIONS,
                subscriptionTurnInput(messages, profile.advanced.contextMessages),
                profile.resolveEffort(ModelDefaults.capability(profile)).level?.wire, profile.advanced.safeTimeoutSeconds), onActivity) {
                usage?.result?.value = it
            }
        } catch (signedOut: NativeCompletionFailure) {
            // A signed-out CLI refused before any model answered: a confirmed rejection that names its remedy.
            if (!signedOut.signedOut) throw signedOut
            throw LlmTransportException(401, null, "Claude Code is not signed in",
                ProviderRejection(code = "claude_code_signed_out", refusal = ProviderRefusal.SIGN_IN)).apply { initCause(signedOut) }
        }
    }

    internal companion object {
        const val CHAT_INSTRUCTIONS = "Ты отвечаешь в чате MagicPaper. Не читай и не меняй файлы и не запускай команды. " +
            "Для свежих сведений можешь искать в интернете. Верни только полезный ответ пользователю."
    }
}
