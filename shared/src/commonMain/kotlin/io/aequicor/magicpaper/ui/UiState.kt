package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.DocArticle
import io.aequicor.magicpaper.domain.PluginState
import io.aequicor.magicpaper.plugins.MagicPlugin

/** Экраны минималистичной навигации. */
enum class Screen { CHAT, PLUGINS, DOCS, SETTINGS }

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
)
