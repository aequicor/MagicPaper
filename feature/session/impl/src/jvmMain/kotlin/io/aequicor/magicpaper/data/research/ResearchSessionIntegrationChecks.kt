package io.aequicor.magicpaper.data.research

import io.aequicor.magicpaper.domain.SessionIntegrationCheck
import io.aequicor.magicpaper.domain.SessionIntegrationCheckRunner
import java.nio.file.Paths

/** Uses the existing OS sandbox and command policy; no unrestricted process fallback. */
class ResearchSessionIntegrationChecks : SessionIntegrationCheckRunner {
    override suspend fun run(path: String, id: String, command: List<String>): SessionIntegrationCheck {
        val result = ResearchCheckRunner.shared.run(Paths.get(path), id, command)
        return SessionIntegrationCheck(command, result.exitCode, result.output, result.blockedReason)
    }
    override fun abort(id: String) = ResearchCheckRunner.shared.abort(id)
    override suspend fun reconcile(id: String) = ResearchCheckRunner.shared.reconcile(id)
}
