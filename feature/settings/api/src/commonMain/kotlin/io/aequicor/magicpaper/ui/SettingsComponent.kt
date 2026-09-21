package io.aequicor.magicpaper.ui

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.flow.StateFlow

enum class SettingsPage { OVERVIEW, MODELS, ENGINES, PROFILE, WELCOME, COMPUTER }
data class SettingsInput(val page: SettingsPage = SettingsPage.OVERVIEW, val profileId: String? = null)
sealed interface SettingsOutput {
    data object Overview : SettingsOutput
    data object Back : SettingsOutput
    data object Chat : SettingsOutput
    data object Projects : SettingsOutput
    data object Plugins : SettingsOutput
    data object Docs : SettingsOutput
    data object Models : SettingsOutput
    data object Engines : SettingsOutput
    data object Computer : SettingsOutput
    data class Profile(val id: String) : SettingsOutput
}
sealed interface SettingsAction {
    data class Save(val settings: AppSettings) : SettingsAction
    data class SaveProfile(val profile: LlmProfile) : SettingsAction
}

/** Public component boundary; its renderer is supplied by the owning feature. */
interface SettingsComponent {
    val state: StateFlow<SettingsState>
    fun onAction(action: SettingsAction)
    @Composable fun Content()
    fun interface Factory {
        fun create(context: ComponentContext, input: SettingsInput, onOutput: (SettingsOutput) -> Unit): SettingsComponent
    }
}
