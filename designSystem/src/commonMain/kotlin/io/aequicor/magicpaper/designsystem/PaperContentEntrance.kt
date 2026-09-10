package io.aequicor.magicpaper.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment

/**
 * Reveal newly arriving content once within its saved composition identity.
 * Existing content is visible immediately, including when a lazy item is restored.
 * Updating the content or [animate] never removes it or restarts the entrance.
 */
@Composable
public fun PaperContentEntrance(animate: Boolean, content: @Composable () -> Unit) {
    var appeared by rememberSaveable { mutableStateOf(false) }
    val visibility = remember { MutableTransitionState(!animate || appeared).apply { targetState = true } }
    SideEffect { appeared = true }
    AnimatedVisibility(visibility,
        enter = fadeIn(tween(180)) + expandVertically(tween(220), expandFrom = Alignment.Top),
        exit = ExitTransition.None,
    ) { content() }
}
