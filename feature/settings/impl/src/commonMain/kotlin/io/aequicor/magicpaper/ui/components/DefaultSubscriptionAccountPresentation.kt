package io.aequicor.magicpaper.ui.components

import androidx.compose.runtime.Composable
import io.aequicor.magicpaper.ui.OpenAiSubscriptionUi
import io.aequicor.magicpaper.ui.screens.SubscriptionAccountContent

object DefaultSubscriptionAccountPresentation : SubscriptionAccountPresentation {
    @Composable override fun Content(state: OpenAiSubscriptionUi, onAction: (SubscriptionAccountAction) -> Unit) =
        SubscriptionAccountContent(state, onAction)
}
