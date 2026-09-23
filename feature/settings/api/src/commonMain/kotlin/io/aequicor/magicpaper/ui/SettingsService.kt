package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.flow.StateFlow

/** What a user-confirmed erase removes: everything, or the sessions and what they own while configuration stays. */
enum class ApplicationDataReset { ALL, SESSIONS }

interface SettingsService {
    val state: StateFlow<SettingsState>
    suspend fun start()
    fun dismissNotice()
    suspend fun checkSearchConnection(connection: SearchConnection, draft: AppSettings): SearchConnectionResult
    fun finishOnboarding(settings: AppSettings, onboardingProfile: LlmProfile? = null)
    fun restartOnboarding()
    fun saveSettings(settings: AppSettings)
    /** Save only persisted access policy, preserving any unfinished overview form. */
    fun saveComputerAccess(computer: ComputerAccess, application: ComputerAccess)
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
    fun saveMediaSelection(kind: MediaKind, selection: MediaModelSelection?, verify: Boolean = false)
    suspend fun readMediaAsset(asset: MediaAsset): ByteArray
    suspend fun mediaAssetPath(asset: MediaAsset): String?
    /** Recover the existing probe only; this must never submit a replacement generation. */
    suspend fun recoverMediaResult(mediaId: String): GeneratedMedia?
    fun exportProfile()
    fun importProfile()
    fun wipeAll()
    /** Erases projects, coding sessions and chats with every journal of their execution; keeps settings, provider
     *  profiles with their keys, models, skills, plugin preferences and the subscription sign-in. */
    fun resetSessions()
    fun prepareProfileEditor()
    suspend fun close()
}
