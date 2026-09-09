package io.aequicor.magicpaper.domain

/** A creation-time receipt for the user, never an instruction or an assistant turn. */
fun sessionContextReport(profile: LlmProfile?, environment: String, prompt: String, skills: String): String {
    val text = buildString {
        appendLine("Системное сообщение · контекст новой сессии")
        appendLine("Снимок настроек на момент создания. Изменения модели, режима и навыков применяются к последующим запускам.")
        appendLine()
        appendLine(environment)
        if (profile == null) appendLine("Модель: не выбрана") else {
            appendLine("Профиль: ${profile.name} · ${profile.provider}")
            appendLine("Модель: ${profile.modelId.ifBlank { "не выбрана" }}")
            appendLine("Усилие рассуждения: ${profile.effortSelectionFor().label}")
            appendLine("Температура: ${profile.advanced.safeTemperature ?: "по умолчанию движка"}; top-p: ${profile.advanced.safeTopP ?: "по умолчанию движка"}")
            appendLine("Лимит контекста профиля: ${profile.advanced.safeContextLimit}; лимит ответа профиля: ${profile.advanced.safeMaxTokens}")
            appendLine("Фактические лимиты и поддержка параметров зависят от модели и движка.")
        }
        appendLine()
        appendLine("Системный промпт MagicPaper")
        appendLine(prompt)
        appendLine()
        appendLine("Подключённые навыки")
        appendLine(skills)
        appendLine()
        append("Это сведения о входных инструкциях, а не внутренние рассуждения модели. Служебный отчёт не отправляется модели как история диалога.")
    }
    return profile?.apiKey?.takeIf { it.isNotBlank() }?.let { text.replace(it, "[ключ скрыт]") } ?: text
}
