package io.aequicor.magicpaper.domain

interface SessionIntegrationCheckRunner {
    suspend fun run(path: String, id: String, command: List<String>): SessionIntegrationCheck
    fun abort(id: String)
    suspend fun reconcile(id: String)
}