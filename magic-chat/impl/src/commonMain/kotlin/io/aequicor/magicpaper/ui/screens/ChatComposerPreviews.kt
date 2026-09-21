package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.magicpaper.designsystem.PaperSurface
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.SessionMediaTools
import io.aequicor.magicpaper.ui.components.DefaultChatPresentation
import io.aequicor.magicpaper.ui.components.LocalChatPresentation

@Preview(name = "Provider chat", group = "Chat composer", widthDp = 720, heightDp = 440)
@Preview(name = "Provider chat narrow", group = "Chat composer", widthDp = 390, heightDp = 560)
@Preview(name = "Provider chat large text", group = "Chat composer", widthDp = 720, heightDp = 700, fontScale = 2f)
@Composable
internal fun ChatComposerPreview(withOptions: Boolean = true, recovery: Boolean = false) {
    PaperTheme {
        CompositionLocalProvider(LocalChatPresentation provides DefaultChatPresentation) { PaperSurface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Composer(enabled = true, paused = recovery, resumeAction = ResearchResumeAction.CHECK_SAVED_RESPONSE, session = ChatSession("preview", "Вопрос", 1, 1),
                    profiles = emptyList(), activeProfileId = "", onSend = { _, _ -> },
                    onOpenSwitcher = {}, onPickAttachments = { _, _ -> },
                    mediaOptions = if (withOptions) ({ SessionMediaToolOptions(SessionMediaTools(), emptyMap(), { _, _ -> }, {}) }) else null)
            }
        } }
    }
}

@Preview(name = "No provider options", group = "Chat composer", widthDp = 390, heightDp = 240)
@Composable
internal fun ChatComposerWithoutOptionsPreview() = ChatComposerPreview(withOptions = false)

@Preview(name = "Saved answer check", group = "Chat recovery", widthDp = 720, heightDp = 440)
@Preview(name = "Saved answer check narrow", group = "Chat recovery", widthDp = 390, heightDp = 560)
@Preview(name = "Saved answer check large text", group = "Chat recovery", widthDp = 720, heightDp = 700, fontScale = 2f)
@Composable
internal fun ChatRecoveryComposerPreview() = ChatComposerPreview(withOptions = false, recovery = true)
