package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

@Serializable
data class ResearchResource(
    val id: String,
    val title: String,
    val url: String = "",
    val attachment: Attachment? = null,
    val discovered: Boolean = false,
    val snippet: String = "",
    /** Read by the application for this run only; page bodies never enter saved notebooks. */
    @kotlinx.serialization.Transient val readableText: String? = null,
)

/** Only navigable HTTP(S) references are admitted; credentials and whitespace are rejected. */
fun researchUrl(value: String): String? {
    val match = Regex("^(https?)://([^\\s/?#:@]+)(:[0-9]+)?([/?#][^\\s]*)?$", RegexOption.IGNORE_CASE)
        .matchEntire(value.trim()) ?: return null
    val host = match.groupValues[2].lowercase()
    if (host.startsWith('.') || host.endsWith('.') || host.any { !it.isLetterOrDigit() && it !in ".-" }) return null
    val port = match.groupValues[3]
    if (port.isNotEmpty() && port.drop(1).toIntOrNull()?.let { it in 1..65535 } != true) return null
    val path = match.groupValues[4].substringBefore('#').ifEmpty { "/" }
    return "${match.groupValues[1].lowercase()}://$host${match.groupValues[3]}$path"
}

val ChatSession.researchChatId: String get() = researchParentId ?: id

enum class ResearchResourceScope { SHARED, QUESTION }

/** URL identity survives discovery and promotion; file identity survives renaming. */
val ResearchResource.key: String get() = if (url.isNotEmpty()) "url:$url" else "file:${attachment?.id ?: id}"

fun ChatSession.availableResearchResources(notebook: ChatSession): List<ResearchResource> =
    (notebook.resources + questionResources.filterNot {
        it.url in excludedQuestionResourceUrls || it.discovered && it.url in notebook.excludedResourceUrls
    }).distinctBy { it.key }.filterNot { it.key in disabledResourceKeys }

/** Fixed application-owned label; raw provider errors must not enter research activity. */
const val RESEARCH_MODEL_FAILURE = "Не удалось получить ответ от модели"

/** Activity is an observable operation log, not a copy of private model reasoning or the answer. */
fun List<CodingStep>.researchActivity(): List<CodingStep> = filterNot { it.kind == CodingStepKind.ANSWER }
    .map { if (it.kind == CodingStepKind.THINKING || it.kind == CodingStepKind.SUMMARY)
        it.copy(title = "Анализирую материалы", result = "")
        else if (it.kind == CodingStepKind.ERROR) it.copy(
            title = if (it.title == RESEARCH_MODEL_FAILURE) RESEARCH_MODEL_FAILURE else "Не удалось выполнить действие", result = "")
        else it.copy(result = "") }
