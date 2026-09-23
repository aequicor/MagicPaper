package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.logging.AppLog

import androidx.compose.runtime.*
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.essenty.lifecycle.doOnResume
import com.arkivanov.essenty.lifecycle.doOnPause
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.ui.screens.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlin.time.TimeSource

class DefaultCodingComponent(
    context: ComponentContext,
    private val service: DefaultCodingService,
    private val input: CodingInput,
    filePicker: FilePicker,
    val projectSkills: ProjectSkills?,
    private val onOutput: (CodingOutput) -> Unit,
) : CodingComponent, CodingService by LoggingCodingService(service) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val picker = AttachmentSelection(filePicker, scope)
    private var activationJob: Job? = null
    private data class Activation(val ready: Boolean = false, val error: String? = null)
    private data class Display(val content: CodingState, val activation: Activation, val attachmentError: String?)
    private val activation = MutableStateFlow(Activation())
    private val display = combine(service.state, activation, picker.error, ::Display)
        .stateIn(scope, SharingStarted.Eagerly, Display(service.state.value, activation.value, picker.error.value))
    val planningChat get() = service.planningChat
    fun composerDraft(id: String) = service.composerDraft(id)
    init {
        context.lifecycle.doOnResume {
            activationJob = scope.launch {
                activation.value = Activation()
                val opening = TimeSource.Monotonic.markNow()
                val fields = listOfNotNull(input.projectId?.let { "projectId" to it }, input.sessionId?.let { "sessionId" to it }).toMap()
                fun elapsed() = fields + ("elapsedMs" to opening.elapsedNow().inWholeMilliseconds.toString())
                AppLog.info("coding-screen", "open.started", fields)
                try {
                    service.activate(input.projectId, input.sessionId)
                    activation.value = Activation(ready = true)
                    AppLog.info("coding-screen", "open.finished", elapsed())
                } catch (cancelled: CancellationException) {
                    AppLog.info("coding-screen", "open.cancelled", elapsed())
                    throw cancelled
                }
                catch (failure: Exception) { AppLog.error("coding-screen", "open.failed", failure, elapsed()); activation.value = Activation(error = "Не удалось открыть сессию.") }
            }
        }
        context.lifecycle.doOnPause { activationJob?.cancel(); activation.value = Activation(); service.setVisible(false) }
        context.lifecycle.doOnDestroy { scope.cancel() }
    }
    fun pickAttachments(count: Int, result: (List<Attachment>) -> Unit) = picker.pickAttachments(count, result)
    fun pasteAttachments(count: Int, result: (List<Attachment>) -> Unit) = picker.pasteAttachments(count, result)
    fun openModelsSettings() = onOutput(CodingOutput.Models)
    override fun onAction(action: CodingAction) {
        if (!activation.value.ready) return
        val id = input.sessionId ?: state.value.coding.currentSessionId
        if (state.value.coding.sessions.none { it.session.id == id && it.session.projectId == input.projectId }) return
        when(action) {
            is CodingAction.Send -> id?.let { sendCodingPromptTo(it, action.text, action.attachments) }
            CodingAction.Stop -> id?.let(::abortCodingSession)
        }
    }
    @Composable override fun Content() {
        val screen by display.collectAsState()
        val current = screen.content
        val ready = screen.activation.ready
        val failure = screen.activation.error
        screen.attachmentError?.let { PaperText(it) }
        when {
            failure != null -> PaperText(requireNotNull(failure))
            !ready -> PaperText("Загрузка…")
            input.projectId != null && current.coding.projects.none { it.id == input.projectId } -> PaperText("Проект не найден")
            input.sessionId != null && current.coding.sessions.none { it.session.id == input.sessionId && it.session.projectId == input.projectId } -> PaperText("Сессия не найдена")
            else -> CodingScreen(this, current.coding, profiles = current.modelPickerProfiles,
                activeProfileId = current.settings.activeLlmProfileId,
                defaultEngine = current.settings.defaultCodingEngine,
                showProjectsPanel = false, globalFeatureFlags = current.settings.featureFlags)
        }
    }
}

class DefaultCodingComponentFactory(private val service: DefaultCodingService, private val filePicker: FilePicker,
    private val projectSkills: ProjectSkills? = null) : CodingComponent.Factory {
    override fun create(context: ComponentContext, input: CodingInput, onOutput: (CodingOutput) -> Unit): CodingComponent =
        DefaultCodingComponent(context, service, input, filePicker, projectSkills, onOutput)
}
