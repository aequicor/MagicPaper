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

class DefaultCodingComponent(
    context: ComponentContext,
    private val service: DefaultCodingService,
    private val input: CodingInput,
    filePicker: FilePicker,
    val projectSkills: ProjectSkills?,
    private val onOutput: (CodingOutput) -> Unit,
) : CodingComponent, CodingService by service {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val picker = AttachmentSelection(filePicker, scope)
    private var activationJob: Job? = null
    private val activated = MutableStateFlow(false)
    private val activationError = MutableStateFlow<String?>(null)
    val planningChat get() = service.planningChat
    val requestPins get() = service.requestPins
    val usage get() = service.usage
    val immunityActions get() = service.immunityActions
    val quarantineRecovery get() = service.quarantineRecovery
    val questionnaireDrafts get() = service.questionnaireDrafts
    fun composerDraft(id: String) = service.composerDraft(id)
    init {
        context.lifecycle.doOnResume {
            activationJob = scope.launch {
                activated.value = false
                activationError.value = null
                try {
                    service.activate(input.projectId, input.sessionId)
                    activated.value = true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { AppLog.error("coding-screen", "open.failed", failure); activationError.value = "Не удалось открыть сессию." }
            }
        }
        context.lifecycle.doOnPause { activationJob?.cancel(); activated.value = false; service.setVisible(false) }
        context.lifecycle.doOnDestroy { scope.cancel() }
    }
    fun pickAttachments(count: Int, result: (List<Attachment>) -> Unit) = picker.pickAttachments(count, result)
    fun pasteAttachments(count: Int, result: (List<Attachment>) -> Unit) = picker.pasteAttachments(count, result)
    fun openModelsSettings() = onOutput(CodingOutput.Models)
    override fun onAction(action: CodingAction) {
        if (!activated.value) return
        val id = input.sessionId ?: state.value.coding.currentSessionId
        if (state.value.coding.sessions.none { it.session.id == id && it.session.projectId == input.projectId }) return
        when(action) {
            is CodingAction.Send -> id?.let { sendCodingPromptTo(it, action.text, action.attachments) }
            CodingAction.Stop -> id?.let(::abortCodingSession)
        }
    }
    @Composable override fun Content() {
        val current by state.collectAsState()
        val ready by activated.collectAsState()
        val failure by activationError.collectAsState()
        val error by picker.error.collectAsState()
        error?.let { PaperText(it) }
        when {
            failure != null -> PaperText(requireNotNull(failure))
            !ready -> PaperText("Загрузка…")
            input.projectId != null && current.coding.projects.none { it.id == input.projectId } -> PaperText("Проект не найден")
            input.sessionId != null && current.coding.sessions.none { it.session.id == input.sessionId && it.session.projectId == input.projectId } -> PaperText("Сессия не найдена")
            else -> CodingScreen(this, current.coding, profiles = current.availableLlmProfiles,
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
