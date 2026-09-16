package io.aequicor.magicpaper.ui.screens

import io.aequicor.magicpaper.domain.ResearchResource
import io.aequicor.magicpaper.domain.researchUrl

internal data class ResearchSourcePresentation(val title: String, val detail: String,
    val file: Boolean, val iconUrl: String? = null)

internal fun ResearchResource.presentation(): ResearchSourcePresentation {
    attachment?.let { file ->
        val name = file.name.ifBlank { title }
        val extension = name.substringAfterLast('.', "").takeIf { it.length in 1..10 && it.all(Char::isLetterOrDigit) }
        val type = extension?.uppercase() ?: when (file.mimeType.substringBefore(';').lowercase()) {
            "application/pdf" -> "PDF"
            "text/plain" -> "TXT"
            "text/markdown" -> "MD"
            "text/csv" -> "CSV"
            "application/json" -> "JSON"
            "application/msword" -> "DOC"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "DOCX"
            "image/png" -> "PNG"
            "image/jpeg" -> "JPEG"
            else -> "Файл"
        }
        return ResearchSourcePresentation(name, type, file = true)
    }
    val normalized = researchUrl(url)
    val origin = normalized?.substringBefore("://")?.let { scheme ->
        "$scheme://${normalized.substringAfter("://").substringBefore('/').substringBefore('?')}"
    }
    val domain = origin?.substringAfter("://")?.removePrefix("www.") ?: "Сайт"
    return ResearchSourcePresentation(title.ifBlank { domain }, domain, file = false, iconUrl = origin?.let { "$it/favicon.ico" })
}
