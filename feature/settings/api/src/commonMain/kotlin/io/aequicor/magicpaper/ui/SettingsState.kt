package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProviderType


data class SettingsState(
    val settings: AppSettings = AppSettings(),
    val settingsSaving: Boolean = false,
    val storageInfo: String = "",
    val showWelcome: Boolean = false,
    val llmProfiles: List<LlmProfile> = emptyList(),
    val editorModels: List<ModelDefaults.DiscoveredModel> = emptyList(),
    val editorModelsLoading: Boolean = false,
    val editorModelsFor: String? = null,
    val editorModelsError: String? = null,
    val connectionTesting: Boolean = false,
    val modelDescriptions: List<ModelDossier> = emptyList(),
    val descriptionsGenerating: Boolean = false,
    val descriptionsProgress: String? = null,
    val descriptionsErrors: List<String> = emptyList(),
    val descriptionsContext: String? = null,
    val catalogRefreshing: Set<String> = emptySet(),
    val openAiSubscription: OpenAiSubscriptionUi = OpenAiSubscriptionUi(),
    val claudeSubscription: ClaudeSubscriptionUi = ClaudeSubscriptionUi(),
    val notice: String? = null,
    val mediaSupported: Boolean = false,
    val mediaConnections: Map<MediaKind, MediaConnectionStatus> = emptyMap(),
) {
    val availableLlmProfiles: List<LlmProfile> get() = llmProfiles.filter { it.enabled && available(it.provider) }
    val modelPickerProfiles: List<LlmProfile> get() = llmProfiles.map {
        if (!available(it.provider)) it.copy(enabled = false) else it
    }

    /** A subscription provider needs its desktop transport; every other provider is reachable anywhere. */
    fun available(provider: ProviderType): Boolean = when (provider) {
        ProviderType.OPENAI_SUBSCRIPTION -> openAiSubscription.available
        ProviderType.ANTHROPIC_SUBSCRIPTION -> claudeSubscription.available
        else -> true
    }
}

/** The Claude subscription kept by the installed Claude Code; available=false where there is no desktop runtime. */
data class ClaudeSubscriptionUi(
    val available: Boolean = false,
    val checking: Boolean = false,
    /** null until checked, or when Claude Code cannot tell. */
    val signedIn: Boolean? = null,
    val signingIn: Boolean = false,
    val signingOut: Boolean = false,
    val error: String? = null,
)
/** Состояние desktop-входа через ChatGPT; available=false на Android/Web. */
data class OpenAiSubscriptionUi(
    val available: Boolean = false,
    val loading: Boolean = false,
    val signingIn: Boolean = false,
    val account: OpenAiSubscriptionAccount? = null,
    val login: OpenAiSubscriptionLogin? = null,
    val error: String? = null,
)
