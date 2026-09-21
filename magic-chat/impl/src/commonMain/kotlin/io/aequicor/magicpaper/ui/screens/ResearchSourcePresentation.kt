package io.aequicor.magicpaper.ui.screens

import io.aequicor.magicpaper.domain.ResearchResource
import io.aequicor.magicpaper.domain.researchUrl

internal data class ResearchSourcePresentation(val title: String, val detail: String,
    val file: Boolean, val iconUrl: String? = null, val browserUrl: String? = null)

/** External navigation is HTTPS-only. Reuse source validation, but retain the
 * fragment for article anchors (researchUrl intentionally strips it for identity). */
internal fun researchSourceBrowserUrl(value: String): String? {
    val raw = value.trim()
    if (raw.any { it.isISOControl() || it == '\\' }) return null
    val normalized = researchUrl(raw)?.takeIf { it.startsWith("https://") } ?: return null
    return normalized + if ('#' in raw) "#${raw.substringAfter('#')}" else ""
}

/** Compact status belongs to presentation; retain the full reason in accessible detail. */
internal fun researchSourceProblemLabel(problem: String?): String? = problem?.let {
    Regex("HTTP\\s+(\\d{3})").find(it)?.let { match -> return "HTTP ${match.groupValues[1]}" }
    when {
        "время ожидания" in it || "timeout" in it.lowercase() -> "Timeout"
        "CAPTCHA" in it -> "CAPTCHA"
        "требуется вход" in it -> "Вход"
        "подписк" in it -> "Подписка"
        "JavaScript" in it -> "JavaScript"
        "нет доступного текста" in it -> "Нет текста"
        "Формат" in it -> "Формат"
        "слишком большая" in it -> "Размер"
        else -> "Ошибка"
    }
}

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
    return ResearchSourcePresentation(title.ifBlank { domain }, domain, file = false,
        iconUrl = origin?.let { "$it/favicon.ico" }, browserUrl = researchSourceBrowserUrl(url))
}
