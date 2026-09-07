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

/** Desktop-роутер coding-движка: Codex для подписки ChatGPT, pi для API-профилей. */
class DesktopCodingRuntime(
    private val pi: PiCodingRuntime,
    private val subscription: CodexAppServerOpenAiSubscription,
) : CodingRuntime {
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
    ): Flow<CodingEvent> = if (profile?.provider == ProviderType.OPENAI_SUBSCRIPTION) {
        subscription.runCoding(project, session, prompt, profile, attachments)
    } else {
        pi.run(project, session, prompt, profile, attachments)
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
