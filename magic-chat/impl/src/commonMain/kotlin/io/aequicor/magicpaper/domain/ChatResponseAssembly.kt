package io.aequicor.magicpaper.domain

/** Identical pure validation for live completion and owner-verified saved provider text. No source I/O. */
internal fun assembleChatResponse(responseId: String, text: String, context: ChatMachine.ResponseContext?,
    activity: List<CodingStep>, orderedContent: List<TranscriptBlock>): SessionAnswer {
    val checked = context?.checked?.associateBy { it.url }.orEmpty()
    val unverified = context == null || researchReferences(text).any { reference ->
        val source = checked[reference.url]
        source?.problem != null || (context.sourceTask && source == null)
    }
    val verified = if (unverified) {
        "Ответ содержит ссылки на непрочитанные страницы, поэтому его не удалось подтвердить. " +
            "Повторите запрос по доступным источникам или добавьте текст нужного материала файлом."
    } else text
    val unavailable = context?.checked.orEmpty().filter { it.problem != null }
    val notice = if (unavailable.isEmpty()) "" else unavailable.joinToString("\n", prefix =
        "\n\nНедоступные источники исключены из ссылок ответа. Можно повторить запрос для новой проверки:\n") {
        "- ${it.title}: ${it.problem}."
    }
    val reply = researchReply(verified)
    val content = if (unverified) listOf(TranscriptBlock.Markdown("$responseId:notice", reply.text)) +
        orderedContent.filterIsInstance<TranscriptBlock.Media>() else orderedContent.researchContent()
    val completeText = content.filterIsInstance<TranscriptBlock.Markdown>().joinToString("\n\n") { it.text }.ifBlank { reply.text }
    return SessionAnswer(completeText + notice, context?.sources.orEmpty(), context?.attachments.orEmpty(), activity, reply.followUps,
        content + if (notice.isBlank()) emptyList() else listOf(TranscriptBlock.Markdown("$responseId:sources", notice)))
}
