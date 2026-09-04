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
import io.aequicor.magicpaper.domain.RuntimePhase
import io.aequicor.magicpaper.domain.RuntimeStatus
import io.aequicor.magicpaper.domain.aggregateCodingStatus
import io.aequicor.magicpaper.domain.codingStatusOf
import io.aequicor.magicpaper.plugins.MagicPlugin

/** Экраны минималистичной навигации. */
enum class Screen { CHAT, CODING, PLUGINS, DOCS, SETTINGS }

/** Кодинг-сессия в UI: журнал плюс живой прогон и статус для индикатора. */
data class CodingSessionUi(
    val session: CodingSession,
    val messages: List<CodingMessage> = emptyList(),
    val draft: CodingDraft = CodingDraft(),
    val running: Boolean = false,
) {
    /**
     * Кружок активности: красный, когда агент реально работает; жёлтый —
     * пока прогон запущен, но ждёт ответ модели (или журнал закончился
     * вопросом/ошибкой); зелёный — ждёт запроса.
     */
    val status: CodingSessionStatus
        get() = when {
            running && draft.awaitingModel -> CodingSessionStatus.WAITING
            running -> CodingSessionStatus.WORKING
            else -> codingStatusOf(messages)
        }
}

/** Состояние раздела «Проекты и код»: проект ↔ несколько кодинг-сессий. */
data class CodingUi(
    val projects: List<CodingProject> = emptyList(),
    val current: CodingProject? = null,
    /** Сессии текущего проекта с журналами и живыми прогонами. */
    val sessions: List<CodingSessionUi> = emptyList(),
    val currentSessionId: String? = null,
    /** Сводный кружок проекта (самый срочный статус среди его сессий). */
    val projectStatuses: Map<String, CodingSessionStatus> = emptyMap(),
    val runtime: RuntimeStatus = RuntimeStatus(RuntimePhase.UNKNOWN),
    val installing: Boolean = false,
) {
    val currentSession: CodingSessionUi?
        get() = sessions.firstOrNull { it.session.id == currentSessionId } ?: sessions.firstOrNull()

    fun statusOf(projectId: String, fallback: CodingSessionStatus = CodingSessionStatus.IDLE): CodingSessionStatus {
        val own = sessions.filter { it.session.projectId == projectId }
        return if (own.isNotEmpty()) {
            aggregateCodingStatus(own.map { it.status })
        } else {
            projectStatuses[projectId] ?: fallback
        }
    }
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
    /** Открыт ли переключатель модели в чате. */
    val modelSwitcherOpen: Boolean = false,
    /** Профиль, открытый в редакторе настроек (для перехода из чата). */
    val editingLlmProfileId: String? = null,
    /** Модели, загруженные у провайдера черновика (этап 6). */
    val editorModels: List<io.aequicor.magicpaper.domain.DiscoveredModel> = emptyList(),
    val editorModelsLoading: Boolean = false,
    val editorModelsError: String? = null,
    val connectionTesting: Boolean = false,
)
