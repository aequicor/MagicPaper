package io.aequicor.magicpaper.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import io.aequicor.magicpaper.domain.Attachment
import io.aequicor.magicpaper.domain.InteractionKind

internal val LocalOpenQuestionnaire = staticCompositionLocalOf<(InteractionKind, String) -> Unit> { { _, _ -> } }

class CodingComposerDraft {
    val text = mutableStateOf("")
    val attachments = mutableStateOf<List<Attachment>>(emptyList())
}
