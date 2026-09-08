package io.aequicor.magicpaper.domain

/** Display only the explanation from an unfinished routing/plan envelope. Never execute a preview. */
internal fun planningReplyPreview(raw: String): String {
    var source = raw.trimStart()
    if (source.startsWith('`')) {
        val newline = source.indexOf('\n')
        if (!source.startsWith("```") || newline < 0) return ""
        source = source.substring(newline + 1).trimStart()
    }
    if (!source.startsWith('{')) return source
    data class Token(val text: String, val next: Int, val complete: Boolean)
    fun stringAt(start: Int): Token {
        val text = StringBuilder()
        var i = start + 1
        while (i < source.length) {
            val c = source[i++]
            if (c == '"') return Token(text.toString(), i, true)
            if (c != '\\') { text.append(c); continue }
            if (i == source.length) break
            when (val escaped = source[i++]) {
                '"', '\\', '/' -> text.append(escaped)
                'n' -> text.append('\n')
                'r' -> text.append('\r')
                't' -> text.append('\t')
                'b' -> text.append('\b')
                'f' -> text.append('\u000C')
                'u' -> {
                    if (i + 4 > source.length) break
                    val code = source.substring(i, i + 4).toIntOrNull(16) ?: break
                    text.append(code.toChar()); i += 4
                }
                else -> break
            }
        }
        // A delta can split a UTF-16 surrogate pair, including a JSON unicode escape.
        if (text.lastOrNull() in '\uD800'..'\uDBFF') text.deleteAt(text.lastIndex)
        return Token(text.toString(), i, false)
    }
    var depth = 0
    var i = 0
    while (i < source.length) {
        when (source[i]) {
            '{', '[' -> { depth++; i++ }
            '}', ']' -> { depth--; i++ }
            '"' -> {
                val key = stringAt(i)
                if (!key.complete) return ""
                i = key.next
                if (depth == 1 && key.text == "reply") {
                    while (i < source.length && source[i].isWhitespace()) i++
                    if (i >= source.length || source[i++] != ':') continue
                    while (i < source.length && source[i].isWhitespace()) i++
                    return if (i < source.length && source[i] == '"') stringAt(i).text else ""
                }
            }
            else -> i++
        }
    }
    return ""
}

internal fun CodingStep.planningPreview(): CodingStep =
    if (kind == CodingStepKind.ANSWER) copy(title = planningReplyPreview(title)) else this

/** Replace one provider fragment in place; hidden activity must never replace the whole answer. */
internal fun List<CodingStep>.withPlanningActivity(raw: CodingStep): List<CodingStep> {
    val step = raw.planningPreview()
    val index = indexOfLast {
        if (step.id.isNotBlank()) it.id == step.id
        else it.kind == step.kind && it.callId == step.callId &&
            (step.callId.isNotBlank() || step.kind in listOf(CodingStepKind.ANSWER, CodingStepKind.THINKING, CodingStepKind.INFO))
    }
    val identified = step.copy(id = if (index >= 0) this[index].id else step.id.ifBlank { io.aequicor.magicpaper.util.Id.new() })
    return if (index < 0) this + identified else mapIndexed { i, old -> if (i == index) identified else old }
}

/** Keep the visible answer's lazy-item identity when the orchestration reply is persisted. */
internal fun CodingMessage.withPlanningDraft(draft: CodingDraft?): CodingMessage {
    if (role != CodingRole.AGENT || draft?.timelineId != id) return this
    val answer = draft.steps.lastOrNull { it.kind == CodingStepKind.ANSWER && it.title.isNotBlank() }
    val savedSteps = steps.ifEmpty {
        if (answer != null && text.isNotBlank()) listOf(CodingStep(CodingStepKind.ANSWER, text)) else emptyList()
    }
    val lastAnswer = savedSteps.indexOfLast { it.kind == CodingStepKind.ANSWER }
    return copy(timelineId = draft.timelineId, steps = savedSteps.mapIndexed { index, step ->
        if (index == lastAnswer && step.id.isBlank() && answer != null) step.copy(id = answer.id) else step
    })
}
