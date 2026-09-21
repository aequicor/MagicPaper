package io.aequicor.magicpaper.ui.components

import androidx.compose.runtime.Composable
import io.aequicor.magicpaper.ui.OpenAiSubscriptionUi
import io.aequicor.magicpaper.ui.SettingsService

enum class SubscriptionAccountAction { LOGIN, CANCEL_LOGIN, REFRESH, LOGOUT }

/** Shared provider-account content; consumers supply state and dispatch explicit user actions. */
interface SubscriptionAccountPresentation {
    @Composable fun Content(state: OpenAiSubscriptionUi, onAction: (SubscriptionAccountAction) -> Unit)
}

fun SettingsService.subscriptionAccountAction(action: SubscriptionAccountAction) {
    when (action) {
        SubscriptionAccountAction.LOGIN -> startOpenAiSubscriptionLogin()
        SubscriptionAccountAction.CANCEL_LOGIN -> cancelOpenAiSubscriptionLogin()
        SubscriptionAccountAction.REFRESH -> refreshOpenAiSubscription(true)
        SubscriptionAccountAction.LOGOUT -> logoutOpenAiSubscription()
    }
}
