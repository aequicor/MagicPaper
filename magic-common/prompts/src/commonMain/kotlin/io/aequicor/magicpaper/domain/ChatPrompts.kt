package io.aequicor.magicpaper.domain

/** Complete provider input, assembled without executing a model, skill or tool. */
fun chatPromptMessages(
    profile: LlmProfile,
    history: List<ChatMessage>,
    userText: String,
    attachments: List<Attachment> = emptyList(),
    skills: List<Skill> = emptyList(),
    context: String? = null,
    system: String = CHAT_SYSTEM_PROMPT,
): List<LlmMessage> = buildList {
    add(LlmMessage(LlmChatRole.SYSTEM, profile.advanced.systemPromptOverride.ifBlank { system }))
    if (skills.isNotEmpty()) add(LlmMessage(LlmChatRole.SYSTEM, skillsPrompt(skills)))
    if (context != null) add(LlmMessage(LlmChatRole.SYSTEM, context))
    history.takeLast(profile.advanced.contextMessages).forEach { message ->
        // Old attachments remain in saved history; only this turn's attachments are sent again.
        add(LlmMessage(if (message.role == ChatRole.USER) LlmChatRole.USER else LlmChatRole.ASSISTANT, message.text))
    }
    add(LlmMessage(LlmChatRole.USER, userText, attachments))
}

private fun skillsPrompt(skills: List<Skill>): String = buildString {
    appendLine("У тебя есть навыки, подходящие к этой задаче. Следуй их инструкциям:")
    skills.forEachIndexed { i, skill ->
        appendLine()
        appendLine("${i + 1}. ${skill.name} — ${skill.description}")
        append(skill.instructions.trim())
        appendLine()
    }
}

val CHAT_SYSTEM_PROMPT = """
    Ты — MagicPaper, волшебный ассистент в мире мягкой магии. Отвечай кратко, ясно
    и по делу, лёгким дружелюбным тоном, без пафоса. Если дан контекст из документации
    или результатов поиска — опирайся на него и упоминай источники. Если информации
    недостаточно — честно скажи об этом.
""".trimIndent()
