package io.aequicor.magicpaper.ui.screens
import io.aequicor.magicpaper.ui.components.PaperInlineMessageParts
import io.aequicor.magicpaper.ui.components.rememberPaperInlineMessageParts


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import io.aequicor.magicpaper.designsystem.PaperTitleBarLaneGap
import io.aequicor.magicpaper.designsystem.paperChatTopShadow
import io.aequicor.magicpaper.ui.window.LocalWindowToolbarHeight
import androidx.compose.foundation.layout.widthIn
import io.aequicor.magicpaper.designsystem.PaperLazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.Attachment
import io.aequicor.magicpaper.domain.ChatMessage
import io.aequicor.magicpaper.domain.ChatRole
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.CodingEngine
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProfileResolver
import io.aequicor.magicpaper.ui.DefaultChatComponent
import io.aequicor.magicpaper.ui.ChatState
import io.aequicor.magicpaper.ui.components.MessageAttachments
import io.aequicor.magicpaper.ui.components.paperStickToBottom
import io.aequicor.magicpaper.ui.components.PaperChatScrollItem
import io.aequicor.magicpaper.ui.components.PaperChatScrollToBottomButton
import io.aequicor.magicpaper.ui.components.paperChatScrollInput
import io.aequicor.magicpaper.designsystem.LocalPaperColors
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperTextRole
import io.aequicor.magicpaper.domain.fullCopyText
import io.aequicor.magicpaper.domain.ExecutionIntent
import io.aequicor.magicpaper.ui.components.MessageHistoryActions
import io.aequicor.magicpaper.designsystem.PaperResearchReading
import io.aequicor.magicpaper.designsystem.PaperDivider
import io.aequicor.magicpaper.designsystem.paperResearchMessage
import io.aequicor.magicpaper.designsystem.PaperContentEntrance
import io.aequicor.magicpaper.designsystem.PaperResearchReadingMeasure
import io.aequicor.magicpaper.designsystem.paperResearchComposerAlignment
import io.aequicor.magicpaper.designsystem.PaperResearchSourceLink
import io.aequicor.magicpaper.designsystem.PaperResearchSourcesDisclosure
import io.aequicor.magicpaper.designsystem.PaperResearchFollowUps
import io.aequicor.magicpaper.logging.AppLog

/** Research workspace with shared sources and independently resumable questions. */
@Composable
fun ChatScreen(vm: DefaultChatComponent, state: ChatState) {
    // Клавиатуру уже учитывает корневой windowInsetsPadding(WindowInsets.safeDrawing) —
    // ime входит в safeDrawing, поэтому отдельный imePadding здесь не нужен.
    val mediaConnections = vm.mediaGeneration?.state?.collectAsState()?.value.orEmpty()
    val mediaOwner = state.notebook ?: state.current
    val usage = vm.usage?.state?.collectAsState()?.value
    val profile = ProfileResolver.resolve(state.current, state.settings, state.availableLlmProfiles)
    val draft = state.current?.id?.let { state.drafts[it] }
    val context = usage?.contexts?.get("chat:${state.current?.id}")?.takeIf { it.model == profile?.modelId }
    ResearchWorkspace(vm, state) {
        PaperResearchReading {
            MessagesList(state.current, state.busy, draft = draft, modifier = Modifier.fillMaxSize(),
                onEdit = { id, text -> vm.editMessage(checkNotNull(state.current).id, id, text) },
                onDelete = { id -> vm.deleteMessage(checkNotNull(state.current).id, id) },
                onFork = { id -> vm.forkSession(checkNotNull(state.current).id, id) },
                onFollowUp = { id, question -> vm.sendFollowUp(checkNotNull(state.current).id, id, question) },
                onPause = vm::pause, onResume = { vm.resume("", emptyList()) }, footer = {
                    Composer(
                        draftSession = vm.composerDraft,
                        mediaOptions = mediaOwner?.let { owner -> {
                            SessionMediaToolOptions(owner.mediaTools, mediaConnections,
                                onChange = { kind, allowed -> vm.setMediaToolEnabled(owner.id, kind, allowed) },
                                onSettings = vm::openModelsSettings)
                        } },
                        enabled = true,
                        resolvedProfile = profile,
                        contextUsage = context,
                        contextCompacting = draft?.steps?.any { it.systemEvent?.phase == CompactionPhase.STARTED && it.running } == true,
                        busy = state.busy,
                        paused = state.current?.pendingRun != null && !state.busy,
                        onPause = vm::pause,
                        onResume = vm::resume,
                        onClarify = vm::clarify,
                        session = state.current,
                        profiles = state.modelPickerProfiles,
                        activeProfileId = state.settings.activeLlmProfileId,
                        onSend = { text, attachments -> vm.send(text, attachments) },
                        onOpenSwitcher = { vm.toggleModelSwitcher(true) },
                        defaultEngine = state.settings.defaultCodingEngine,
                        onEngineChange = vm::selectChatEngine,
                        onPickAttachments = { already, onPicked -> vm.pickAttachments(already, onPicked) },
                        onPasteAttachments = { already, onPicked -> vm.pasteAttachments(already, onPicked) },
                    )
                })
        }
    }
}
