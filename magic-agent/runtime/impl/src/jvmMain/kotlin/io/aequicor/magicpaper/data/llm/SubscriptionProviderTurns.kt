package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.last

/** Core AI host adapter; native provider lifetime belongs to the desktop factory's shared library. */
internal class SubscriptionProviderTurns(
    private val library: NativeProviderLibrary,
    private val token: suspend () -> String,
) {
    suspend fun turn(profile: LlmProfile, messages: List<LlmMessage>, tools: List<LlmToolDefinition>,
        exchanges: List<LlmToolExchange>): LlmToolTurn {
        require(profile.provider == ProviderType.OPENAI_SUBSCRIPTION)
        check(library.prepare().last().phase == NativeInstallationPhase.READY) {
            "Не удалось подготовить подключение по подписке. Откройте настройки движков и повторите установку."
        }
        val usage = currentCoroutineContext()[UsageCall]
        return library.turn(profile.forModel(), messages, tools, exchanges, token()) { usage?.result?.value = it }
    }
}
