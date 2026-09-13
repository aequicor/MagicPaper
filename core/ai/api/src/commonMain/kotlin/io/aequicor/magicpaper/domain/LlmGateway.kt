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
    suspend fun completeWithActivity(profile: LlmProfile, messages: List<LlmMessage>, onActivity: (CodingStep) -> Unit): String {
        onActivity(CodingStep(CodingStepKind.INFO, "Ожидание ответа модели ${profile.shortLabel}. Подключение возвращает итоговый ответ."))
        return complete(profile, messages)
    }
}