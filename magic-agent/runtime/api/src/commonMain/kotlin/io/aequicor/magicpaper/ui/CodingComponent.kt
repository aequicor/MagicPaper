package io.aequicor.magicpaper.ui

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.flow.StateFlow

data class CodingInput(val projectId: String? = null, val sessionId: String? = null)
sealed interface CodingOutput { data object Models : CodingOutput }
sealed interface CodingAction {
    data class Send(val text: String, val attachments: List<Attachment> = emptyList()) : CodingAction
    data object Stop : CodingAction
}

/** Public component boundary; its renderer is supplied by the owning feature. */
interface CodingComponent {
    val state: StateFlow<CodingState>
    fun onAction(action: CodingAction)
    @Composable fun Content()
    fun interface Factory {
        fun create(context: ComponentContext, input: CodingInput, onOutput: (CodingOutput) -> Unit): CodingComponent
    }
}
