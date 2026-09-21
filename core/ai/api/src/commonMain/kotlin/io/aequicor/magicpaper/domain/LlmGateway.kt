package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.util.Id

interface LlmGateway {
    suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String
    /** A single provider turn. The caller owns tool execution and supplies every result for continuation. */
    suspend fun turn(profile: LlmProfile, messages: List<LlmMessage>, tools: List<LlmToolDefinition>,
        exchanges: List<LlmToolExchange> = emptyList()): LlmToolTurn {
        if (tools.isNotEmpty() || exchanges.isNotEmpty()) throw UnsupportedOperationException("Подключение не поддерживает вызовы инструментов.")
        return LlmToolTurn(text = complete(profile, messages), provider = profile.provider)
    }
    suspend fun completeWithActivity(profile: LlmProfile, messages: List<LlmMessage>, onActivity: (CodingStep) -> Unit): String {
        onActivity(CodingStep(CodingStepKind.INFO, "Ожидание ответа модели ${profile.shortLabel}. Подключение возвращает итоговый ответ."))
        return complete(profile, messages)
    }
}

/** Stable presentation text; the diagnostic reason never contains provider payloads or arguments. */
class LlmToolProtocolException(val reason: String, cause: Throwable? = null) :
    IllegalStateException("Модель вернула неполный или некорректный ответ. Повторите запрос.", cause)
