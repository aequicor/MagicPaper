package io.aequicor.magicpaper.domain

/** Only secret answer values are ephemeral. Question definitions retain exact-call identity. */
internal fun redactQuestionnaire(record: RuntimeQuestionnaireRecord): RuntimeQuestionnaireRecord {
    val secretIds = record.request.questions.filter { it.secret }.map { it.id }.toSet()
    fun redact(answers: List<PlanningAnswer>) = answers.map { if (it.questionId in secretIds) PlanningAnswer(it.questionId) else it }
    return record.copy(request = record.request.copy(initialAnswers = redact(record.request.initialAnswers), submitting = false, error = null),
        answers = redact(record.answers), answersRedacted = record.answersRedacted || (secretIds.isNotEmpty() && record.answers.isNotEmpty()))
}
