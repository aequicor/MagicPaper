package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.flow.StateFlow

interface SettingsService {
    val state: StateFlow<SettingsState>
    suspend fun start()
    fun dismissNotice()
    suspend fun checkSearchConnection(connection: SearchConnection, draft: AppSettings): SearchConnectionResult
    fun finishOnboarding(settings: AppSettings, onboardingProfile: LlmProfile? = null)
    fun restartOnboarding()
    fun saveSettings(settings: AppSettings)
    fun saveLlmProfile(profile: LlmProfile)
    fun deleteLlmProfile(id: String)
    fun setLlmProfileEnabled(id: String, enabled: Boolean)
    fun setDefaultModel(selection: ModelSelection)
    fun updateModelLibrary(profile: LlmProfile)
    fun refreshModelCatalog(id: String)
    fun saveModelDescription(dossier: ModelDossier)
    fun generateModelDescriptions()
    fun refreshOpenAiSubscription(refreshToken: Boolean = false)
    fun startOpenAiSubscriptionLogin()
    fun cancelOpenAiSubscriptionLogin()
    fun logoutOpenAiSubscription()
    fun openAiSubscriptionSignedIn(): Boolean
    fun fetchModels(draft: LlmProfile)
    fun refreshProfileParameters(profileId: String)
    fun testConnection(draft: LlmProfile)
    fun exportProfile()
    fun importProfile()
    fun wipeAll()
    fun prepareProfileEditor()
    suspend fun close()
}
