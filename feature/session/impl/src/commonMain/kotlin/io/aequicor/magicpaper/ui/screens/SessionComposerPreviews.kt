package io.aequicor.magicpaper.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.magicpaper.ui.components.CodingComposerDraft
import io.aequicor.magicpaper.designsystem.PaperTheme

@Preview(name = "Running", group = "Session input", widthDp = 1000, heightDp = 260)
@Preview(name = "Running narrow", group = "Session input", widthDp = 390, heightDp = 260)
@Composable
internal fun RunningSessionComposerPreview() {
    val draft = remember { CodingComposerDraft().apply { text.value = "Уточнение к текущей задаче" } }
    PaperTheme {
        CodingComposer(state = draft, enabled = true, busy = true,
            onSend = { _, _ -> }, onClarify = { _, _ -> }, onAbort = {}, onPickAttachments = { _, _ -> })
    }
}

@Preview(name = "Chat running narrow", group = "Session input", widthDp = 390, heightDp = 240)
@Preview(name = "Chat running", group = "Session input", widthDp = 1000, heightDp = 240)
@Composable
internal fun RunningChatComposerPreview() {
    PaperTheme {
        Composer(enabled = true, busy = true, session = null, profiles = emptyList(), activeProfileId = "",
            onSend = { _, _ -> }, onOpenSwitcher = {}, onPickAttachments = { _, _ -> })
    }
}
