package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.logging.AppLog
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal data class ResearchReply(val text: String, val followUps: List<String> = emptyList())

internal const val RESEARCH_FOLLOW_UPS_MARKER = "<!-- magicpaper:follow-ups"

/** Optional answer metadata, shared by completion, old-history presentation and click validation. */
internal fun researchReply(text: String, streaming: Boolean = false): ResearchReply {
    val marker = text.lastIndexOf(RESEARCH_FOLLOW_UPS_MARKER)
    if (marker >= 0 && (marker == 0 || text[marker - 1] == '\n') && !insideCodeFence(text, marker)) {
        val body = text.substring(0, marker).trimEnd()
        if (streaming) return ResearchReply(body)
        val tail = text.substring(marker + RESEARCH_FOLLOW_UPS_MARKER.length).trim()
        if (tail.endsWith("-->")) {
            val payload = tail.removeSuffix("-->").trim()
            if (payload.length <= 2048) {
                try {
                    val questions = Json.decodeFromString<List<String>>(payload).map { it.trim() }
                    if (questions.size in 1..3 && questions.all { it.isNotEmpty() && it.length <= 240 && '\n' !in it } &&
                        questions.distinct().size == questions.size) return ResearchReply(body, questions)
                } catch (_: SerializationException) {
                    // Preserve the original answer when optional metadata is malformed.
                    AppLog.debug("chat", "answer.follow-ups.invalid-format")
                }
            }
        }
        return ResearchReply(text)
    }
    if (streaming) {
        // Do not flash a control marker when the stream splits it between tokens.
        val start = text.lastIndexOf('\n') + 1
        val suffix = text.substring(start)
        if (suffix.isNotEmpty() && RESEARCH_FOLLOW_UPS_MARKER.startsWith(suffix) && !insideCodeFence(text, start))
            return ResearchReply(text.substring(0, start).trimEnd())
        return ResearchReply(text)
    }
    return legacyResearchReply(text)
}

internal fun ChatMessage.researchReply(): ResearchReply =
    if (role == ChatRole.USER || followUps.isNotEmpty()) ResearchReply(text, followUps)
    else researchReply(text)

private val legacyOption = Regex("^([1-3])[.)] +(.+)$")
private val legacyAction = Regex("^(Разобрать|Составить|Сравнить|Проверить|Подобрать|Обсудить|Уточнить|Написать)(?:\\s|$)", RegexOption.IGNORE_CASE)

/** Only the old, distinctive continuation menu; ordinary numbered article lists stay prose. */
private fun legacyResearchReply(text: String): ResearchReply {
    val end = text.trimEnd()
    val start = end.lastIndexOf("\n\n")
    if (start < 0 || end.length - start > 1024 || insideCodeFence(end, start)) return ResearchReply(text)
    val lines = end.substring(start + 2).lines()
    if (lines.size !in 2..3) return ResearchReply(text)
    val options = lines.mapIndexed { index, line ->
        val match = legacyOption.matchEntire(line.trim()) ?: return ResearchReply(text)
        if (match.groupValues[1].toInt() != index + 1) return ResearchReply(text)
        match.groupValues[2].trim().takeIf { it.length <= 240 && legacyAction.containsMatchIn(it) }
            ?: return ResearchReply(text)
    }
    if (options.none { it.startsWith("Написать статью:", ignoreCase = true) }) return ResearchReply(text)
    return ResearchReply(end.substring(0, start).trimEnd(), options)
}

private fun insideCodeFence(text: String, end: Int): Boolean {
    var fence: Char? = null
    text.substring(0, end).lineSequence().forEach { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            if (fence == null) fence = trimmed.first() else if (fence == trimmed.first()) fence = null
        }
    }
    return fence != null
}
