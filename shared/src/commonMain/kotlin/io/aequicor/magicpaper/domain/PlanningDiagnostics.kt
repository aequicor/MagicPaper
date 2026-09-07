package io.aequicor.magicpaper.domain

/** Persist diagnostic evidence, excluding credentials even when an engine echoes a connection. */
object PlanningDiagnostics {
    private val authorization = Regex("(?i)(authorization[\\s:=]+(?:bearer|basic)\\s+)[^\\s,;]+")
    private val parameters = Regex("(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|password)[\\s\"':=]+)[^\\s\"',;}&]+")
    fun redact(text: String, secrets: Set<String> = emptySet()): String {
        var result = text
        secrets.filter { it.isNotBlank() }.sortedByDescending { it.length }.forEach { result = result.replace(it, "[скрыто]") }
        result = authorization.replace(result) { "${it.groupValues[1]}[скрыто]" }
        return parameters.replace(result) { "${it.groupValues[1]}[скрыто]" }
    }
}
