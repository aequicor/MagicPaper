package io.aequicor.magicpaper.ui

import androidx.compose.runtime.*
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.essenty.lifecycle.doOnResume
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.ui.screens.*
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*

class DefaultSettingsComponent(
    context: ComponentContext,
    private val service: DefaultSettingsService,
    val plugins: PluginService,
    val draftRepository: DraftRepository,
    val draftBlobs: DraftBlobStore,
    val input: SettingsInput,
    private val onOutput: (SettingsOutput) -> Unit,
    val contributions: SettingsContributions = SettingsContributions(),
) : SettingsComponent, SettingsService by service {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val drafts get() = service.drafts
    internal fun saveOverviewSettings(settings: AppSettings) = service.saveOverviewSettings(settings)
    private var draftCreationError by mutableStateOf(false)
    private var profileCreationCandidate: LlmProfile? = null
    fun createProfileDraft() {
        scope.launch {
            try {
                draftCreationError = false
                val profile = profileCreationCandidate ?: LlmProfile(id = Id.new(), name = "Мой источник", createdAt = Id.now()).also { profileCreationCandidate = it }
                val draft = drafts.profile(profile)
                draft.update { it.copy(profile = profile) }
                draft.awaitSaved()
                editLlmProfile(profile.id)
                profileCreationCandidate = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { logPersistenceFailure("SettingsComponent", "create_profile_draft_failed", failure); draftCreationError = true }
        }
    }
    internal fun saveVariantDraft(profile: LlmProfile, model: String, onSaved: () -> Unit) = service.saveVariantDraft(profile, model, onSaved)
    internal fun saveDescriptionDraft(dossier: ModelDossier, onSaved: () -> Unit) = service.saveDescriptionDraft(dossier, onSaved)
    init {
        require(input.page != SettingsPage.ENGINES && input.page != SettingsPage.COMPUTER) {
            "${input.page} is contributed by a host and none registered it"
        }
        context.lifecycle.doOnResume { if (input.page == SettingsPage.PROFILE) service.prepareProfileEditor() }
        context.lifecycle.doOnDestroy { scope.cancel() }
    }
    fun openContribution(entry: SettingsNavigationEntry) = onOutput(entry.destination)
    fun openChat() = onOutput(SettingsOutput.Chat)
    fun openPlugins() = onOutput(SettingsOutput.Plugins)
    fun openDocs() = onOutput(SettingsOutput.Docs)
    fun openModelsSettings() = onOutput(SettingsOutput.Models)
    fun closeModelsSettings() = onOutput(SettingsOutput.Back)
    fun closeLlmProfileEditor() = onOutput(SettingsOutput.Back)
    fun editLlmProfile(id: String) = onOutput(SettingsOutput.Profile(id))
    fun togglePlugin(id: String, enabled: Boolean) = plugins.togglePlugin(id, enabled)
    override fun onAction(action: SettingsAction) {
        when(action) {
            is SettingsAction.Save -> service.saveSettings(action.settings)
            is SettingsAction.SaveProfile -> service.saveLlmProfile(action.profile)
        }
    }
    @Composable override fun Content() {
        val current by state.collectAsState()
        if (draftCreationError) {
            PaperText("Не удалось создать черновик", color = LocalPaperColors.current.error)
            PaperButton("Повторить", ::createProfileDraft, kind = PaperButtonKind.QUIET)
        }
        when(input.page) {
            SettingsPage.OVERVIEW -> SettingsScreen(this, current)
            SettingsPage.MODELS -> ModelsSettings(this, current)
            // Rejected at construction: a host contributes these pages and no page stands in for one.
            SettingsPage.ENGINES, SettingsPage.COMPUTER -> Unit
            SettingsPage.WELCOME -> WelcomeScreen(this, current)
            SettingsPage.PROFILE -> {
                var restored by remember(input.profileId) { mutableStateOf<LlmProfile?>(null) }
                var loaded by remember(input.profileId) { mutableStateOf(false) }
                var failed by remember(input.profileId) { mutableStateOf(false) }
                var restoreAttempt by remember(input.profileId) { mutableIntStateOf(0) }
                LaunchedEffect(input.profileId, restoreAttempt) {
                    try { failed = false; loaded = false; restored = input.profileId?.let { drafts.existingProfile(it) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { logPersistenceFailure("SettingsComponent", "restore_profile_draft_failed", failure); failed = true }
                    finally { loaded = true }
                }
                val profile = current.llmProfiles.firstOrNull { it.id == input.profileId } ?: restored
                if (drafts.isProfileDeleted(input.profileId)) PaperText("Профиль не найден")
                else if (failed) {
                    PaperText("Не удалось восстановить черновик", color = LocalPaperColors.current.error)
                    PaperButton("Повторить", { restoreAttempt++ }, kind = PaperButtonKind.QUIET)
                }
                else if (profile == null && loaded) PaperText("Профиль не найден")
                else if (profile == null) PaperText("Загрузка…")
                else ProfileEditor(this, profile, current)
            }
        }
    }
}

class DefaultSettingsComponentFactory(
    private val service: DefaultSettingsService,
    private val plugins: PluginService,
    private val draftRepository: DraftRepository,
    private val draftBlobs: DraftBlobStore,
    private val contributions: SettingsContributions = SettingsContributions(),
) : SettingsComponent.Factory {
    override fun create(context: ComponentContext, input: SettingsInput, onOutput: (SettingsOutput) -> Unit): SettingsComponent =
        contributions.pages.firstOrNull { it.page == input.page }?.factory?.create(context, input, onOutput)
            ?: DefaultSettingsComponent(context, service, plugins, draftRepository, draftBlobs, input, onOutput, contributions)
}
