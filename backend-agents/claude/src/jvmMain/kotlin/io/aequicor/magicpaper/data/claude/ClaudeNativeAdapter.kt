package io.aequicor.magicpaper.data.claude

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.CodingEngine

/** Claude Code descriptor: what the engine is and says about itself; behavior lives in [ClaudeBackendAgent]. */
class ClaudeNativeAdapter {
    val descriptor = BackendAgentDescriptor(
        CodingEngine.CLAUDE_CODE,
        "Claude Code",
        setOf(BackendAgentCapability.EXTERNAL_INSTALLATION),
        "Claude Code · работа с файлами и командами проекта",
        "Подключения Anthropic: ключ API или собственный вход Claude Code.",
        "Навыки и плагины Claude Code определяются его конфигурацией при запуске; список ниже относится к пакетам MagicPaper.",
        CLAUDE_FILE_TOOL_INSTRUCTIONS,
        CLAUDE_CODING_INSTRUCTIONS,
    )
}

internal const val CLAUDE_FILE_TOOL_INSTRUCTIONS =
    "Claude Code: читай файлы инструментом Read, ищи через Grep и Glob, вноси точечные изменения инструментом Edit, " +
        "а Write используй только для новых файлов. Не заменяй Edit полной перезаписью существующего файла."

internal const val CLAUDE_CODING_INSTRUCTIONS =
    "Ты coding-агент MagicPaper. Изменяй исходники внутри открытой папки проекта, выполняй задачу до результата и кратко сообщи итог. " +
        "Для сборки используй установленный toolchain и обычный кеш Gradle (GRADLE_USER_HOME из окружения или ~/.gradle); " +
        "не переноси кеш в проект ради обхода ограничений. Подтверждения доступа MagicPaper для этого движка не запрашивает: " +
        "не выполняй разрушающих действий вне папки проекта."
