package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.util.Id

interface SkillInstructionRuntime {
    suspend fun answer(query: String, history: List<ChatMessage>, profile: LlmProfile?, attachments: List<Attachment>): String?
    companion object {
        const val MAX_HISTORY = 6
        const val MAX_CONTEXT = 24_000
        private const val MODE_RECEIPT = "Режим: только текст; доступ к файлам, сети и процессам не предоставлен."
        private val secrets = Regex("(?i)(-----BEGIN [A-Z ]*PRIVATE KEY-----|\\bBearer\\s+\\S+|\\bsk-[A-Za-z0-9_-]{8,}|\\b(?:api[_-]?key|token|password|secret)\\s*[:=]\\s*\\S+)")
        fun containsSecret(text: String, configuredKey: String) =
            secrets.containsMatchIn(text) || (configuredKey.isNotBlank() && text.contains(configuredKey))
    }
}
