package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.LlmGateway
import io.aequicor.magicpaper.domain.LlmMessage
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProviderType
import io.aequicor.magicpaper.domain.forModel

/**
 * Роутер по типу провайдера (композиция, паттерн «стратегия»):
 * потребители видят один [LlmGateway], а формат запроса выбирается
 * по [LlmProfile.provider]. Новый транспорт = новая запись в карте.
 */
class RoutingLlmGateway(
    private val transports: Map<ProviderType, LlmGateway>,
    private val usage: io.aequicor.magicpaper.domain.UsageLedger? = null,
) : LlmGateway {

    override suspend fun completeWithActivity(profile: LlmProfile, messages: List<LlmMessage>, onActivity: (io.aequicor.magicpaper.domain.CodingStep) -> Unit): String {
        val transport = transports[profile.provider] ?: error("Нет транспорта для провайдера ${profile.provider}.")
        val effective = profile.forModel()
        return usage?.measure(effective) { transport.completeWithActivity(effective, messages, onActivity) }
            ?: transport.completeWithActivity(effective, messages, onActivity)
    }

    override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
        val transport = transports[profile.provider]
            ?: error("Нет транспорта для провайдера ${profile.provider}.")
        val effective = profile.forModel()
        return usage?.measure(effective) { transport.complete(effective, messages) } ?: transport.complete(effective, messages)
    }
}
