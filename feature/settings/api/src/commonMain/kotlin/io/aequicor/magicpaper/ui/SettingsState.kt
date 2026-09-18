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
    val notice: String? = null,
    val mediaSupported: Boolean = false,
    val mediaConnections: Map<MediaKind, MediaConnectionStatus> = emptyMap(),
) {
    val availableLlmProfiles: List<LlmProfile> get() = llmProfiles.filter {
        it.enabled && (it.provider != ProviderType.OPENAI_SUBSCRIPTION || openAiSubscription.available)
    }
    val modelPickerProfiles: List<LlmProfile> get() = llmProfiles.map {
        if (it.provider == ProviderType.OPENAI_SUBSCRIPTION && !openAiSubscription.available) it.copy(enabled = false) else it
    }
}
/** Состояние desktop-входа через ChatGPT; available=false на Android/Web. */
data class OpenAiSubscriptionUi(
    val available: Boolean = false,
    val loading: Boolean = false,
    val signingIn: Boolean = false,
    val account: OpenAiSubscriptionAccount? = null,
    val login: OpenAiSubscriptionLogin? = null,
    val error: String? = null,
)
