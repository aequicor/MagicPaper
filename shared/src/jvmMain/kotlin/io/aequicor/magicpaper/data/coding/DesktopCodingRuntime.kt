package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription
import io.aequicor.magicpaper.domain.Attachment
import io.aequicor.magicpaper.domain.CodingEvent
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingRuntime
import io.aequicor.magicpaper.domain.CodingSession
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProviderType
import io.aequicor.magicpaper.domain.RuntimeStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.CancellationException
import io.aequicor.magicpaper.domain.CodingSkillProtection

/** Desktop-роутер coding-движка: Codex для подписки ChatGPT, pi для API-профилей. */
class DesktopCodingRuntime(
    private val pi: PiCodingRuntime,
    private val subscription: CodexAppServerOpenAiSubscription,
    override val projectSkills: io.aequicor.magicpaper.domain.ProjectSkills? = null,
    private val skillSnapshot: suspend (String) -> List<io.aequicor.magicpaper.domain.SkillInstruction> = { emptyList() },
) : CodingRuntime {
    override val approvals = subscription.codingApprovals
    override suspend fun respondApproval(id: String, decision: io.aequicor.magicpaper.domain.CodingApprovalDecision) =
        subscription.respondCodingApproval(id, decision)
    override val supported: Boolean = true
    override suspend fun preflight(profile: LlmProfile) {
        if (profile.provider == ProviderType.OPENAI_SUBSCRIPTION) {
            check(subscription.account().signedIn) { "Нужна авторизация ChatGPT в настройках источника" }
        } else {
            val state = pi.ensureReady().last()
            check(state.ready) { state.detail.ifBlank { "Coding-движок недоступен" } }
        }
    }
    override suspend fun reconcile(sessionId: String) {
        pi.reconcile(sessionId)
        subscription.reconcileCoding(sessionId)
    }
    override val rootPath: String get() = pi.rootPath
    override suspend fun status(): RuntimeStatus = pi.status()
    override fun ensureReady(): Flow<RuntimeStatus> = pi.ensureReady()

    override fun run(
        project: CodingProject,
        session: CodingSession,
        prompt: String,
        profile: LlmProfile?,
        attachments: List<Attachment>,
    ): Flow<CodingEvent> = flow {
        val adapter = if (profile?.provider == ProviderType.OPENAI_SUBSCRIPTION) "Codex" else "Pi"
        val runId = java.util.UUID.randomUUID().toString()
        val selected = try { skillSnapshot(project.id) } catch (e: CancellationException) { throw e } catch (_: Exception) {
            emit(CodingEvent.Failed("SKILLS $runId: привязки или пакеты повреждены; запуск заблокирован."))
            emit(CodingEvent.Finished)
            return@flow
        }
        if (selected.isNotEmpty()) {
            emit(CodingEvent.Notice("SKILLS run=$runId project=${project.id} session=${session.id} adapter=$adapter\n" +
                selected.joinToString("\n") { "${it.id}@${it.version} sha256=${it.checksum}; заявлено=${it.permissions}" } +
                "\nПередано: нет. Предоставлено пакету: нет. Результат задачи: не проверен."))
            emit(CodingEvent.Failed(CodingSkillProtection.reason(adapter)))
            emit(CodingEvent.Finished)
            return@flow
        }
        emit(CodingEvent.Notice("SKILLS run=$runId project=${project.id} session=${session.id} adapter=$adapter: подключённых пакетов нет; пакету не предоставлены полномочия."))
        emitAll(if (profile?.provider == ProviderType.OPENAI_SUBSCRIPTION) {
            subscription.runCoding(project, session, prompt, profile, attachments)
        } else {
            pi.run(project, session, prompt, profile, attachments)
        })
    }

    override fun abort(sessionId: String) {
        subscription.abortCoding(sessionId)
        pi.abort(sessionId)
    }

    override fun abortAll() {
        subscription.abortAllCoding()
        pi.abortAll()
    }

    override suspend fun uninstall() = pi.uninstall()
}
