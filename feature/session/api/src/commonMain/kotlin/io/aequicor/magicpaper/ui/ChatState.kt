package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProviderType


data class ChatState(
    val settings: AppSettings = AppSettings(),
    val llmProfiles: List<LlmProfile> = emptyList(),
    val subscriptionAvailable: Boolean = false,
    val subscriptionSignedIn: Boolean = false,
    val sessions: List<ChatSession> = emptyList(),
    val current: ChatSession? = null,
    val busy: Boolean = false,
    val notice: String? = null,
) {
    val notebooks: List<ChatSession> get() = sessions.filter { it.researchParentId == null }
    val notebook: ChatSession? get() = current?.let { question -> sessions.firstOrNull { it.id == question.researchChatId } }
    val questions: List<ChatSession> get() = notebook?.let { root ->
        sessions.filter { it.researchChatId == root.id }.sortedBy { it.createdAt }
    }.orEmpty()
    val availableLlmProfiles: List<LlmProfile> get() = llmProfiles.filter {
        it.provider != ProviderType.OPENAI_SUBSCRIPTION || subscriptionAvailable
    }
}
