package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.CodingDraft
import io.aequicor.magicpaper.domain.CodingMessage
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.DocArticle
import io.aequicor.magicpaper.domain.PluginState
import io.aequicor.magicpaper.domain.RuntimePhase
import io.aequicor.magicpaper.domain.RuntimeStatus
import io.aequicor.magicpaper.plugins.MagicPlugin

/** Экраны минималистичной навигации. */
enum class Screen { CHAT, CODING, PLUGINS, DOCS, SETTINGS }

/** Состояние раздела «Проекты и код». */
data class CodingUi(
    val projects: List<CodingProject> = emptyList(),
    val current: CodingProject? = null,
    val messages: List<CodingMessage> = emptyList(),
    val draft: CodingDraft = CodingDraft(),
    val busy: Boolean = false,
    val runtime: RuntimeStatus = RuntimeStatus(RuntimePhase.UNKNOWN),
    val installing: Boolean = false,
)

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
)
