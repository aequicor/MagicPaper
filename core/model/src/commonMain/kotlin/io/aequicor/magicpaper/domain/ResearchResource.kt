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
