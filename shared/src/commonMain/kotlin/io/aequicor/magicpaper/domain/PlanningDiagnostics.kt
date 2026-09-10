package io.aequicor.magicpaper.domain

/** Persist diagnostic evidence, excluding credentials even when an engine echoes a connection. */
object PlanningDiagnostics {
    private val authorization = Regex("(?i)(authorization[\\s:=]+(?:bearer|basic)\\s+)[^\\s,;]+")
    private val parameters = Regex("(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|password)[\\s\"':=]+)[^\\s\"',;}&]+")
    private val bearer = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+=*")
    private val privateKey = Regex("-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----[\\s\\S]*?-----END (?:[A-Z ]+ )?PRIVATE KEY-----")
    private val providerKey = Regex("\\b(?:sk-(?:proj-|ant-)?[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b")
    fun redact(text: String, secrets: Set<String> = emptySet()): String {
        var result = text
        secrets.filter { it.isNotBlank() }.sortedByDescending { it.length }.forEach { result = result.replace(it, "[скрыто]") }
        result = authorization.replace(result) { "${it.groupValues[1]}[скрыто]" }
        result = bearer.replace(result) { "${it.groupValues[1]}[скрыто]" }
        result = privateKey.replace(result, "[скрыто]")
        result = providerKey.replace(result, "[скрыто]")
        return parameters.replace(result) { "${it.groupValues[1]}[скрыто]" }
    }
}
