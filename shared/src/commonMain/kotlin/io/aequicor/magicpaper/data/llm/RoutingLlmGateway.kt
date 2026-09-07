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
) : LlmGateway {

    override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
        val transport = transports[profile.provider]
            ?: error("Нет транспорта для провайдера ${profile.provider}.")
        return transport.complete(profile.forModel(), messages)
    }
}
