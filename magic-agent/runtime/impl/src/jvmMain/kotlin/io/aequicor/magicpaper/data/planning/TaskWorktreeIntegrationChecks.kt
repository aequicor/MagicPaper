package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.domain.SessionIntegrationCheck
import io.aequicor.magicpaper.domain.SessionIntegrationCheckRunner
import io.aequicor.magicpaper.domain.checks.*
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * Managed copies retain normal file/network access. The application check owner still requires process
 * containment and confirmed cleanup; no plain-process fallback can report an unobserved descendant as stopped.
 * [id] is the caller's journaled verification operation plus command index, never a generated retry ID.
 */
class TaskWorktreeIntegrationChecks(private val checks: CommandChecks) : SessionIntegrationCheckRunner {
    override suspend fun run(path: String, id: String, command: List<String>): SessionIntegrationCheck {
        require(command.isNotEmpty() && command.size <= 128 && command.all { it.length <= 16_384 && '\u0000' !in it })
        require(java.nio.file.Files.isDirectory(Paths.get(path))) { "Рабочая копия задачи недоступна" }
        val workspace = Paths.get(path).toRealPath().toString()
        val scope = CheckScope(checkWorkspaceId(workspace), id, id, 0)
        val result = checks.run(CheckCommand(CheckRef(scope, id), workspace, command, policy = CheckPolicy.MANAGED_WORKTREE))
        return SessionIntegrationCheck(command, result.exitCode, result.output, result.blockedReason)
    }
    override fun abort(id: String) = checks.abort(id)
    override suspend fun reconcile(id: String) = checks.reconcile(id)
    companion object {
        const val MAX_OUTPUT = 64_000
        const val DEFAULT_TIMEOUT_MILLIS = 15 * 60_000L
        const val TIMEOUT_NOTE = "Проверка остановлена по таймауту"
    }
}

internal fun checkWorkspaceId(path: String): String = MessageDigest.getInstance("SHA-256")
    .digest(path.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
