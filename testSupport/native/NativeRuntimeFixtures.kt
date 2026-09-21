package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.browser.*
import io.aequicor.magicpaper.domain.tools.ToolCommand

/** Fixtures make missing browser capability explicit; production always injects the browser owner. */
val testBrowserSessions = object : BrowserSessions {
    override val available = false
    override fun create(ownerSessionId: String, requestId: String): BrowserSession = object : BrowserSession {
        override val commands = emptyList<ToolCommand<*, *>>()
        override fun close() = Unit
    }
}

/** The engines the fixture below composes. An engine added to the catalog is not part of it, so loops name this list. */
val historicalFixtureEngines = listOf(CodingEngine.PI, CodingEngine.CODEX)

/** Historical integration setups still select their two real isolated processes explicitly. */
fun DesktopCodingRuntime(
    pi: PiCodingRuntime,
    subscription: CodexAppServerOpenAiSubscription,
    skillSnapshot: suspend (String) -> List<SkillInstruction> = { emptyList() },
    skillSelection: suspend (String) -> CodingSkillSelection = { CodingSkillSelection(skillSnapshot(it)) },
    recordSkillRun: suspend (CodingSkillRunRecord) -> Unit = {},
    runObserver: CodingRunObserver = CodingRunObserver { _, events, _ -> events },
    checks: io.aequicor.magicpaper.domain.checks.CommandChecks = testCommandChecks,
): DesktopCodingRuntime = DesktopCodingRuntime(listOf(pi.binding, subscription.binding), subscription.computerUse,
    skillSelection, checks, recordSkillRun, runObserver)

/** Tests without a check scenario must not acquire an implicit process owner. */
val testCommandChecks = object : io.aequicor.magicpaper.domain.checks.CommandChecks {
    override val progress = kotlinx.coroutines.flow.emptyFlow<io.aequicor.magicpaper.domain.checks.CheckProgress>()
    override suspend fun run(command: io.aequicor.magicpaper.domain.checks.CheckCommand): io.aequicor.magicpaper.domain.checks.CheckResult = error("Unexpected command check")
    override suspend fun inspect(ref: io.aequicor.magicpaper.domain.checks.CheckRef): io.aequicor.magicpaper.domain.checks.CheckResult? = error("Unexpected command recovery")
    override fun abort(sessionId: String) = Unit
    override fun abortAll() = Unit
    override suspend fun reconcile(sessionId: String) = Unit
    override suspend fun prepareForReset() = Unit
    override suspend fun resumeAfterReset() = Unit
    override suspend fun close() = Unit
}
