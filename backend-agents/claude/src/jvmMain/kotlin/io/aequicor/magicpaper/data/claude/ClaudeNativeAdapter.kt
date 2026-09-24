package io.aequicor.magicpaper.data.claude

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.CodingEngine
import io.aequicor.magicpaper.domain.CodingModel

/** Claude Code descriptor: what the engine is and says about itself; behavior lives in [ClaudeBackendAgent]. */
class ClaudeNativeAdapter {
    val descriptor = BackendAgentDescriptor(
        CodingEngine.CLAUDE_CODE,
        "Claude Code",
        setOf(BackendAgentCapability.EXTERNAL_INSTALLATION, BackendAgentCapability.NATIVE_MODEL_CATALOG, BackendAgentCapability.NATIVE_SIGN_IN),
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

/**
 * Claude Code has no command that lists its models, so the catalog is declared here by alias: the CLI resolves
 * `opus`, `sonnet`, `haiku` and `fable` to the newest model of that family, and a new release needs no change.
 *
 * Levels are what the CLI's own `/effort` offers for the model the alias resolves to (CLI 2.1.280: `fable` is
 * claude-fable-5-1, `opus` claude-opus-5-5, `sonnet` claude-sonnet-5, `haiku` claude-haiku-4-5, which takes no
 * effort at all). [ClaudeCommand.ULTRACODE] is the CLI's session mode of xhigh effort plus dynamic workflows, open
 * to every xhigh-capable model. The default is the model's own effort when none is chosen ("auto" in the CLI);
 * the names are the ones Claude's model picker shows.
 */
internal object ClaudeModelCatalog {
    const val PROVIDER = "anthropic"
    private val full = listOf("low", "medium", "high", "xhigh", "max", ClaudeCommand.ULTRACODE)
    private val names = mapOf("xhigh" to "extra")
    val models = listOf(
        CodingModel(PROVIDER, "fable", "Fable", levels = full, defaultLevel = "high", acceptsImages = true, levelNames = names),
        CodingModel(PROVIDER, "opus", "Opus", levels = full, defaultLevel = "medium", acceptsImages = true, levelNames = names),
        CodingModel(PROVIDER, "sonnet", "Sonnet", levels = full, defaultLevel = "high", acceptsImages = true, levelNames = names),
        CodingModel(PROVIDER, "haiku", "Haiku", acceptsImages = true),
    )
}
