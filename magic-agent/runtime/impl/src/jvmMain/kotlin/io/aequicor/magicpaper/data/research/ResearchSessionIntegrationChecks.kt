package io.aequicor.magicpaper.data.research

import io.aequicor.magicpaper.data.planning.checkWorkspaceId
import io.aequicor.magicpaper.domain.SessionIntegrationCheck
import io.aequicor.magicpaper.domain.SessionIntegrationCheckRunner
import io.aequicor.magicpaper.domain.checks.*
import java.nio.file.Paths

/** An adapter to the application's durable check owner; the integration request supplies the exact command ID. */
class ResearchSessionIntegrationChecks(private val checks: CommandChecks) : SessionIntegrationCheckRunner {
    override suspend fun run(path: String, id: String, command: List<String>): SessionIntegrationCheck {
        val workspace = Paths.get(path).toRealPath().toString()
        val scope = CheckScope(checkWorkspaceId(workspace), id, id, 0)
        val result = checks.run(CheckCommand(CheckRef(scope, id), workspace, command, policy = CheckPolicy.PROTECTED_PROJECT))
        return SessionIntegrationCheck(command, result.exitCode, result.output, result.blockedReason)
    }
    override fun abort(id: String) = checks.abort(id)
    override suspend fun reconcile(id: String) = checks.reconcile(id)
}
