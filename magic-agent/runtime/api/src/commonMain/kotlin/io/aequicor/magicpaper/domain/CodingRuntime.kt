package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.util.Id

interface CodingRuntime : ChatBackend {
    /** A missing recovery neighbour must be surfaced, never treated as permission to repeat work. */
    val recovery: NativeRunRecovery? get() = null

    /**
     * Глобальные фича-флаги из [AppSettings.featureFlags].
     * Обновляются приложением при смене настроек; per-session override берётся
     * из [CodingSession.featureFlags]. Итоговые флаги: `session.featureFlags.resolve(globalFeatureFlags)`.
     */
    var globalFeatureFlags: FeatureFlagState
        get() = FeatureFlagState()
        set(_) {}

    suspend fun sessionContext(project: CodingProject, session: CodingSession, profile: LlmProfile?): String =
        sessionContextReport(profile, "Проект: ${project.path}\nДвижок: ${session.engine}", "Промпт недоступен для этого движка.", "Сведения о навыках недоступны.")

    override val questionnaires: kotlinx.coroutines.flow.StateFlow<List<UserInteractionRequest>> get() = noRuntimeQuestionnaires
    override suspend fun respondQuestionnaire(id: String, answers: List<PlanningAnswer>) { error("Опросник недоступен") }
    val computerUse: ComputerUse? get() = null

    /**
     * Выбранные в настройках пути к исполняемым файлам движков ([AppSettings.engineExecutables]).
     * Применяются при запуске и при сохранении настроек; идущие прогоны не прерываются.
     */
    fun configureEngineExecutables(paths: Map<CodingEngine, String>) = Unit

    /**
     * Источники нативных каталогов моделей по движкам, у которых такая возможность объявлена.
     * Пусто там, где движка нет (веб, Android): каталог тогда работает только по кэшу.
     */
    val modelSources: Map<CodingEngine, CodingModelSource> get() = emptyMap()
    val approvals: kotlinx.coroutines.flow.StateFlow<List<CodingApproval>> get() = noCodingApprovals
    suspend fun respondApproval(id: String, decision: CodingApprovalDecision) = Unit
    /** Verify engine prerequisites without starting a stage executor. */
    suspend fun preflight(profile: LlmProfile) = Unit
    suspend fun preflight(engine: CodingEngine, profile: LlmProfile) = preflight(profile)
    suspend fun status(engine: CodingEngine): RuntimeStatus = status()
    fun ensureReady(engine: CodingEngine): Flow<RuntimeStatus> = ensureReady()
    suspend fun uninstall(engine: CodingEngine) = uninstall()
    /** The engine's own sign-in flow ([CodingRecovery.SignIn]); returns once it ended. Cancellation stops it. */
    suspend fun signIn(engine: CodingEngine): EngineSignInResult = EngineSignInResult.Failed("Вход для этого движка недоступен.")
    /** Exact terminal engine items only; missing output or a model's report is not completion evidence. */
    suspend fun nativeToolResults(session: CodingSession, callIds: Set<String>): List<CodingEvent.ToolFinished> = emptyList()
    /** Поддерживается ли бэкенд на этой платформе (веб и Android — нет). */
    val supported: Boolean

    /** Корень изолированного рантайма (для отображения в UI). */
    val rootPath: String

    /** Быстрая проверка состояния без установки. */
    suspend fun status(): RuntimeStatus

    /** Подготовка рантайма: проверка и автоустановка зависимостей. Последняя эмиссия — итог. */
    fun ensureReady(): Flow<RuntimeStatus>

    /**
     * Выполнение запроса в директории проекта в контексте кодинг-сессии
     * (её piSessionId продолжает историю). Несколько прогонов разных сессий
     * могут идти параллельно. Поток событий протокола.
     * Вложения рантайм раскладывает в изолированную папку и подставляет пути в промпт.
     */
    fun run(
        project: CodingProject,
        session: CodingSession,
        prompt: String,
        profile: LlmProfile?,
        attachments: List<Attachment> = emptyList(),
    ): Flow<CodingEvent>

    /** Fresh planning context with enforced read-only tools; never falls back to execution. */
    fun runPlanning(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile): Flow<CodingEvent> =
        kotlinx.coroutines.flow.flow {
            emit(CodingEvent.Failed("Чтение проекта при планировании недоступно на этой платформе."))
            emit(CodingEvent.Finished)
        }

    /** Прервать все прогоны (снятие зависимостей, закрытие). */
    fun abortAll()

    /** Полное удаление изолированных зависимостей. */
    suspend fun uninstall()
}