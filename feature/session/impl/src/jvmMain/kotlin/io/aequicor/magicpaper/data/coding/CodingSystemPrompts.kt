package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription
import io.aequicor.magicpaper.data.questionnaire.QuestionnaireTool

// Shared by both backends, including resumed sessions. Application MCP tools do not
// replace the backend's filesystem tools; keep this policy out of read-only modes.
internal val CODING_FILE_TOOL_INSTRUCTIONS = """
    Для чтения и изменения исходников используй штатные инструменты coding-движка.
    Не читай и не переписывай файлы через Python, Node, Perl, shell-перенаправления или
    скрипты замены текста, когда задачу выполняют доступные инструменты чтения и редактирования.
    Перед правкой прочитай нужный участок. Если правка не совпала с исходным текстом,
    перечитай участок и повтори точечную правку штатным инструментом.
    Терминал используй для поиска, сборки, тестов и команд проекта. Скрипты допустимы
    для запуска существующих средств проекта, генераторов и форматтеров либо когда
    штатные инструменты не поддерживают операцию; сначала кратко объясни причину.
    Инструменты magicpaper_ выполняют действия приложения и дополняют инструменты
    coding-движка, а не заменяют чтение и редактирование файлов.
    """.trimIndent()

internal val CODEX_FILE_TOOL_INSTRUCTIONS = """
    Codex: вноси изменения через apply_patch. Для чтения используй доступный инструмент
    чтения файлов; если отдельного инструмента нет, используй rg, sed или cat через
    штатный терминальный инструмент. Если инструменты доступны через functions.exec,
    вызывай их через tools и передавай apply_patch патч, а не скрипт перезаписи файла.
    """.trimIndent()

internal val PI_CODING_INSTRUCTIONS = """
            Pi: read для чтения файлов, edit для точечных изменений, write для создания файлов.
            Не заменяй edit полной перезаписью существующего файла через write или bash.
            Команды имеют таймаут 300 секунд по умолчанию; для заведомо долгой сборки
            явно укажи timeout в секундах. После таймаута проверь результат и состояние
            операции перед повтором: команда могла успеть изменить файлы.
            На Windows используй штатный powershell напрямую, без вложенных cmd /c
            или powershell -Command. Gradle: .\gradlew.bat <задачи> --console=plain;
            сохрани код завершения через exit ${'$'}LASTEXITCODE. Не скрывай прогресс
            долгих команд через -q и конвейер tail: инструмент сам ограничивает вывод.
            Files may contain Russian typography: em dashes (—), guillemets («»…«»), the letter ё.
            In edit tools, copy oldText/newText EXACTLY as read() returned them: do not replace
            an em dash with a hyphen or guillemets with straight quotes, do not drop characters.
            If an edit fails to match, re-read that region and retry with the exact text.
            """.trimIndent()

internal fun codingSystemPrompt(engine: CodingEngine?, planning: Boolean, override: String, research: Boolean = false,
    planningRules: PlanningRulesSnapshot? = null, featureFlags: FeatureFlagState = FeatureFlagState(), session: CodingSession? = null): String {
    val methodology = (planningRules ?: if (planning) PlanningRulesSettings().snapshot() else null)?.effectivePrompt().orEmpty()
    val speedBoost = featureFlags.isEnabled(FeatureFlag.AGENT_SPEED_BOOST)
    return (if (planning) listOf(override, methodology, PLANNING_INSTRUCTIONS, QuestionnaireTool.instructions)
    else if (research) {
        // Optimization 5 (AGENT_SPEED_BOOST): research mode skips coding-specific instructions
        // to save ~500 tokens of system prompt. Research never writes code.
        val base = if (speedBoost) listOf(RESEARCH_INSTRUCTIONS, QuestionnaireTool.instructions)
        else listOf(override, methodology, RESEARCH_INSTRUCTIONS, QuestionnaireTool.instructions)
        if (speedBoost) base + listOfNotNull(override.takeIf { it.isNotBlank() }, methodology.takeIf { it.isNotBlank() }) else base
    } else when (engine) {
        CodingEngine.CODEX -> if (speedBoost) {
            // Optimization 7 (AGENT_SPEED_BOOST): stable prefix first for prompt caching.
            // Invariant instructions (coding + file tools) go first; dynamic (override, methodology) last.
            listOf(CodexAppServerOpenAiSubscription.CODING_INSTRUCTIONS, CODING_FILE_TOOL_INSTRUCTIONS, CODEX_FILE_TOOL_INSTRUCTIONS,
                QuestionnaireTool.instructions, override, methodology)
        } else {
            listOf(QuestionnaireTool.instructions, CodexAppServerOpenAiSubscription.CODING_INSTRUCTIONS, CODING_FILE_TOOL_INSTRUCTIONS, CODEX_FILE_TOOL_INSTRUCTIONS, override)
        }
        CodingEngine.PI -> listOf(CODING_FILE_TOOL_INSTRUCTIONS, PI_CODING_INSTRUCTIONS, QuestionnaireTool.instructions, override)
        null -> listOf("Движок не выбран", override)
    }.let { if (!planning && !research) it + methodology + session?.taskWorktreeInstructions().orEmpty() else it }).filter { it.isNotBlank() }.joinToString("\n\n")
}
