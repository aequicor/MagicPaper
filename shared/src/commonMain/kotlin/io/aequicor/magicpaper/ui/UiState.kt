package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.CodingDraft
import io.aequicor.magicpaper.domain.CodingMessage
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingSession
import io.aequicor.magicpaper.domain.CodingSessionStatus
import io.aequicor.magicpaper.domain.DocArticle
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.PluginState
import io.aequicor.magicpaper.domain.OpenAiSubscriptionAccount
import io.aequicor.magicpaper.domain.OpenAiSubscriptionLogin
import io.aequicor.magicpaper.domain.RuntimePhase
import io.aequicor.magicpaper.domain.RuntimeStatus
import io.aequicor.magicpaper.domain.aggregateCodingStatus
import io.aequicor.magicpaper.domain.codingStatusOf
import io.aequicor.magicpaper.domain.Plan
import io.aequicor.magicpaper.domain.isStageWorking
import io.aequicor.magicpaper.domain.pendingPlanningQuestion
import io.aequicor.magicpaper.domain.isPlannerAnswerWait
import io.aequicor.magicpaper.plugins.MagicPlugin

/** Экраны минималистичной навигации. */
enum class Screen { CHAT, CODING, PLUGINS, DOCS, SETTINGS }

/** Кодинг-сессия в UI: журнал плюс живой прогон и статус для индикатора. */
data class CodingSessionUi(
    val session: CodingSession,
    val messages: List<CodingMessage> = emptyList(),
    val draft: CodingDraft = CodingDraft(),
    val running: Boolean = false,
    val plan: Plan? = null,
) {
    /** Вопрос пользователю имеет приоритет; очередь исполнителей не требует ответа. */
    val status: CodingSessionStatus
        get() {
            if (draft.awaitingApproval) return CodingSessionStatus.WAITING
            val stage = plan?.milestones?.firstOrNull { it.id == session.stageId }
            if (stage != null) return when {
                stage.attempts.lastOrNull()?.let { it.awaitingPlanner && (it.error == null || it.error.isPlannerAnswerWait) } == true -> CodingSessionStatus.IDLE
                stage.attempts.lastOrNull()?.error?.requiresUser == true -> CodingSessionStatus.BLOCKED
                running || plan.isStageWorking(stage) -> CodingSessionStatus.WORKING
                stage.completed -> CodingSessionStatus.IDLE
                else -> CodingSessionStatus.QUEUED
            }
            if (plan != null && session.id == plan.parentSessionId) return when {
                running -> CodingSessionStatus.WORKING
                messages.pendingPlanningQuestion(setOf(plan.id)) != null -> CodingSessionStatus.WAITING
                plan.pendingRequest.isNotBlank() || plan.milestones.any { plan.isStageWorking(it) } -> CodingSessionStatus.WORKING
                plan.issue?.requiresUser == true || plan.finalAttempt?.error?.requiresUser == true ||
                    plan.selectedMilestones.any { !it.completed && it.attempts.lastOrNull()?.error?.requiresUser == true } -> CodingSessionStatus.BLOCKED
                plan.issue != null -> CodingSessionStatus.QUEUED
                plan.confirmedRevision != null -> CodingSessionStatus.IDLE
                else -> codingStatusOf(messages)
            }
            return when {
                running && draft.awaitingModel -> CodingSessionStatus.WAITING
                running -> CodingSessionStatus.WORKING
                session.stageId != null -> CodingSessionStatus.QUEUED
                else -> codingStatusOf(messages)
            }
        }
}

/** Состояние раздела «Проекты и код»: проект ↔ несколько кодинг-сессий. */
data class CodingUi(
    val approvals: List<io.aequicor.magicpaper.domain.CodingApproval> = emptyList(),
    val projects: List<CodingProject> = emptyList(),
    val current: CodingProject? = null,
    /** Сессии текущего проекта с журналами и живыми прогонами. */
    val sessions: List<CodingSessionUi> = emptyList(),
    val currentSessionId: String? = null,
    /** Сводный кружок проекта (самый срочный статус среди его сессий). */
    val projectStatuses: Map<String, CodingSessionStatus> = emptyMap(),
    val runtime: RuntimeStatus = RuntimeStatus(RuntimePhase.UNKNOWN),
    val installing: Boolean = false,
    /** Активная вкладка проекта: диалог с агентом или панель плагина. */
    val sessionMode: CodingSessionMode = CodingSessionMode.DIALOG,
) {
    val currentSession: CodingSessionUi?
        get() = sessions.firstOrNull { it.session.id == currentSessionId } ?: sessions.firstOrNull()

    fun statusOf(projectId: String, fallback: CodingSessionStatus = CodingSessionStatus.IDLE): CodingSessionStatus {
        if (approvals.any { it.projectId == projectId }) return CodingSessionStatus.WAITING
        val own = sessions.filter { it.session.projectId == projectId }
        return if (own.isNotEmpty()) {
            aggregateCodingStatus(own.map { it.status })
        } else {
            projectStatuses[projectId] ?: fallback
        }
    }

    /** Сессии проекта (в состоянии лежат и фоновые сессии других проектов). */
    fun sessionsOf(projectId: String): List<CodingSessionUi> =
        sessions.filter { it.session.projectId == projectId }.map { item ->
            val waiting = approvals.any { it.projectId == projectId &&
                (it.sessionId == item.session.id || it.sessionId == "${item.session.id}-merge" || it.sessionId == "${item.session.id}-delivery") }
            item.copy(draft = item.draft.copy(awaitingApproval = waiting))
        }

    /**
     * Активная сессия проекта: выбранная либо первая, если id сбросился
     * (пустое состояние, удаление). Нужна и левому меню (подсветка), и
     * правой части (какой журнал показывать).
     */
    fun activeSessionIdOf(projectId: String): String? {
        val own = sessionsOf(projectId)
        return (own.firstOrNull { it.session.id == currentSessionId } ?: own.firstOrNull())?.session?.id
    }
}

/** Вкладки кодинг-сессии. */
enum class CodingSessionMode {
    /** Диалог с агентом (журнал проекта). */
    DIALOG,

    /** Панель плагина-носителя панели сессии (например, «Планирование»). */
    PLUGIN_PANEL,
}

/** Единое состояние экрана. Неизменяемый снапшот для Compose. */
data class UiState(
    val screen: Screen = Screen.CHAT,
    val sessions: List<ChatSession> = emptyList(),
    val current: ChatSession? = null,
    val sessionsPanelOpen: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val plugins: List<MagicPlugin> = emptyList(),
    val pluginStates: Map<String, PluginState> = emptyMap(),
    val docsArticles: List<DocArticle> = emptyList(),
    val docsQuery: String = "",
    val busy: Boolean = false,
    val notice: String? = null,
    val storageInfo: String = "",
    /** Показывать ознакомительный тур (первый запуск или сброшен вручную). */
    val showWelcome: Boolean = false,
    val coding: CodingUi = CodingUi(),
    /** Все профили подключения ИИ-провайдеров. */
    val llmProfiles: List<LlmProfile> = emptyList(),
    /** Плагин, поставляющий панель в кодинг-сессию (если включён). */
    val codingPanelPlugin: io.aequicor.magicpaper.plugins.CodingSessionPanel? = null,
    /** Открыт ли переключатель модели в чате. */
    val modelSwitcherOpen: Boolean = false,
    /** Профиль, открытый в редакторе настроек (для перехода из чата). */
    val editingLlmProfileId: String? = null,
    val modelsSettingsOpen: Boolean = false,
    /** Модели, загруженные у провайдера черновика (этап 6). */
    val editorModels: List<io.aequicor.magicpaper.domain.ModelDefaults.DiscoveredModel> = emptyList(),
    val editorModelsLoading: Boolean = false,
    val editorModelsFor: String? = null,
    val editorModelsError: String? = null,
    val connectionTesting: Boolean = false,
    val modelDescriptions: List<io.aequicor.magicpaper.domain.ModelDossier> = emptyList(),
    val descriptionsGenerating: Boolean = false,
    val descriptionsProgress: String? = null,
    val catalogRefreshing: Set<String> = emptySet(),
    val openAiSubscription: OpenAiSubscriptionUi = OpenAiSubscriptionUi(),
) {
    /** Источники, которые можно реально выбрать на текущей платформе. */
    val availableLlmProfiles: List<LlmProfile>
        get() = llmProfiles.filter {
            it.provider != io.aequicor.magicpaper.domain.ProviderType.OPENAI_SUBSCRIPTION || openAiSubscription.available
        }
}

/** Состояние desktop-входа через ChatGPT; available=false на Android/Web. */
data class OpenAiSubscriptionUi(
    val available: Boolean = false,
    val loading: Boolean = false,
    val signingIn: Boolean = false,
    val account: OpenAiSubscriptionAccount? = null,
    val login: OpenAiSubscriptionLogin? = null,
    val error: String? = null,
)
