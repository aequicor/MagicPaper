package io.aequicor.magicpaper.ui

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.flow.StateFlow

data class SkillsInput(val pluginId: String = "skill-shop")
data class SkillsState(val pluginId: String, val title: String = "Навыки", val available: Boolean = false, val enabled: Boolean = false)
sealed interface SkillsOutput { data class OpenPlugin(val id: String) : SkillsOutput }
interface SkillsComponent {
    val state: StateFlow<SkillsState>
    fun enable()
    @Composable fun Content()
    fun interface Factory {
        fun create(context: ComponentContext, input: SkillsInput, onOutput: (SkillsOutput) -> Unit): SkillsComponent
    }
}
