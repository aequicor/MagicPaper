package io.aequicor.magicpaper.designsystem

import androidx.compose.runtime.*
import kotlin.random.Random

/** The host supplies navigation ownership; Paper supplies modal presentation. */
interface PaperDialogLifecycle {
    fun register(id: String, onDismiss: () -> Unit)
    fun unregister(id: String)
}

val LocalPaperDialogLifecycle = staticCompositionLocalOf<PaperDialogLifecycle?> { null }

@Composable
internal fun PaperDialogRegistration(onDismiss: () -> Unit) {
    val owner = LocalPaperDialogLifecycle.current
    val currentDismiss by rememberUpdatedState(onDismiss)
    val id = remember { Random.nextLong().toString(16) + Random.nextLong().toString(16) }
    DisposableEffect(owner, id) {
        owner?.register(id) { currentDismiss() }
        onDispose { owner?.unregister(id) }
    }
}
