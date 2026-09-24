package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProviderType


data class ChatState(
    val settings: AppSettings = AppSettings(),
    val llmProfiles: List<LlmProfile> = emptyList(),
    /** This platform has the desktop transports of the subscription providers (ChatGPT and Claude Code). */
    val subscriptionAvailable: Boolean = false,
    val subscriptionSignedIn: Boolean = false,
    val sessions: List<ChatSession> = emptyList(),
    val current: ChatSession? = null,
    val busy: Boolean = false,
    val notice: String? = null,
    val drafts: Map<String, CodingDraft> = emptyMap(),
    /** Last read failures by question and resource key; rechecked on the next explicit request. */
    val sourceReadProblems: Map<String, Map<String, String>> = emptyMap(),
    val sourceBrowserSupported: Boolean = false,
    val sourceBrowser: ResearchBrowserState? = null,
    /** Configured research search system; shown wherever search is offered or reported. */
    val researchSearchLabel: String = "",
) {
    val notebooks: List<ChatSession> get() = sessions.filter { it.researchParentId == null }
    val notebook: ChatSession? get() = current?.let { question -> sessions.firstOrNull { it.id == question.researchChatId } }
    val questions: List<ChatSession> get() = notebook?.let { root ->
        sessions.filter { it.researchChatId == root.id }.sortedBy { it.createdAt }
    }.orEmpty()
    val availableLlmProfiles: List<LlmProfile> get() = llmProfiles.filter {
        it.enabled && (!it.provider.subscription || subscriptionAvailable)
    }
    val modelPickerProfiles: List<LlmProfile> get() = llmProfiles.map {
        if (it.provider.subscription && !subscriptionAvailable) it.copy(enabled = false) else it
    }
}
