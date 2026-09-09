package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Application-wide adapter; native engine lifetimes and permissions remain unchanged. */
class ToolEnabledCodingRuntime(private val delegate: CodingRuntime, private val host: ToolHost) : CodingRuntime by delegate {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val questionnaires = combine(delegate.questionnaires, host.questions.requests) { native, application -> native + application }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    override suspend fun respondQuestionnaire(id: String, answers: List<PlanningAnswer>) {
        if (host.questions.requests.value.any { it.id == id }) host.questions.respond(id, answers)
        else delegate.respondQuestionnaire(id, answers)
    }
    override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>): Flow<CodingEvent> = flow {
        val context = host.prepareWorker(session.forPendingRun())
        val tools = host.session(context)
        delegate.run(project, session, prompt + "\n\nДля действий приложения используй доступные инструменты magicpaper_. Обычный текст не выполняет действия. " +
            if (context.role == ToolRole.WORKER) "Перед завершением обязательно вызови magicpaper_stage_handoff. После успешной передачи результата заверши ответ." else "",
            profile, attachments).withTools(tools).collect { event ->
            if (event is CodingEvent.Finished && context.role == ToolRole.WORKER && "stage.handoff" !in tools.results.value)
                emit(CodingEvent.Failed("Исполнитель не передал результат через stage.handoff. Работа не принята; изменения сохранены."))
            emit(event)
        }
    }
}
