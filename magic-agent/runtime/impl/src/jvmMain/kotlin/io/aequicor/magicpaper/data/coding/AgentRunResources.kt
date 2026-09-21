package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.browser.BrowserSessions
import io.aequicor.magicpaper.domain.checks.CommandChecks
import io.aequicor.magicpaper.domain.ComputerEndpoint
import io.aequicor.magicpaper.domain.computerCodexConfig
import io.aequicor.magicpaper.domain.NativeComputerUse
import io.aequicor.magicpaper.data.computer.PiComputerExtension
import io.aequicor.magicpaper.data.questionnaire.PiQuestionnaireExtension
import io.aequicor.magicpaper.data.questionnaire.QuestionnaireBridge
import io.aequicor.magicpaper.data.research.PiResearchExtension
import io.aequicor.magicpaper.data.research.ResearchCheckBridge
import io.aequicor.magicpaper.data.tools.AgentToolBridge
import io.aequicor.magicpaper.data.tools.PiAgentToolExtension
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingSession
import io.aequicor.magicpaper.domain.RuntimeQuestionnaireService
import io.aequicor.magicpaper.domain.tools.ToolSession
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Application tools live for the whole run, including native auto-continuations.
 * Only the resolved files, environment and MCP configuration cross into a backend. */
internal class AgentRunResources private constructor(
    private val resources: RunResourceScope,
    private val computer: ComputerEndpoint?,
    private val research: ResearchCheckBridge?,
    private val agent: AgentToolBridge?,
    private val questionnaire: QuestionnaireBridge?,
    val toolNames: List<String>,
    val piExtensions: Map<String, String>,
    val environment: Map<String, String>,
) : AutoCloseable {
    fun codexConfig(base: JsonObject): JsonObject {
        val computerConfig = computerCodexConfig(base, computer?.descriptor)
        val questionnaireConfig = questionnaire?.codexConfig(computerConfig) ?: computerConfig
        val researchConfig = research?.codexConfig(questionnaireConfig) ?: questionnaireConfig
        return agent?.codexConfig(researchConfig) ?: researchConfig
    }

    fun nativeTools() = io.aequicor.magicpaper.backend.NativeAgentTools(toolNames, piExtensions, environment,
        environmentKeys, codexConfig(JsonObject(emptyMap())))

    override fun close() = resources.close()

    companion object {
        fun prepare(
            project: CodingProject,
            session: CodingSession,
            planning: Boolean,
            computerUse: NativeComputerUse?,
            questionnaires: RuntimeQuestionnaireService,
            browser: BrowserSessions,
            checks: CommandChecks,
            tools: ToolSession?,
            cacheToolDefinitions: Boolean = false,
            requestId: String,
        ): AgentRunResources {
            val scope = RunResourceScope()
            try {
                val researchMode = !planning && session.researchMode
                val restricted = planning || researchMode
                val computer = if (restricted) null else computerUse?.grant(session.id)?.let { computerUse.endpoint(it, requestId) }?.let(scope::own)
                val research = if (researchMode) scope.own(ResearchCheckBridge(session, project, checks, requestId)) else null
                val agent = tools?.let {
                    val browserSession = browser.create(it.context.ownerSessionId, it.context.requestId)
                    try { scope.own(AgentToolBridge(it, browser = browserSession, cacheToolDefinitions = cacheToolDefinitions)) }
                    catch (failure: Throwable) {
                        try { browserSession.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                        throw failure
                    }
                }
                val questionnaire = if (planning || tools != null) null else scope.own(QuestionnaireBridge(questionnaires, session))
                val toolNames = tools?.definitions.orEmpty().map { it.wireName }
                return AgentRunResources(scope, computer, research, agent, questionnaire, toolNames,
                    piExtensions = buildMap {
                        if (agent != null) put("agent-tools.mjs", PiAgentToolExtension.source(checkNotNull(tools)))
                        if (research != null) put("research.mjs", PiResearchExtension.source)
                        if (questionnaire != null) put("questionnaire.mjs", PiQuestionnaireExtension.source)
                        if (computer != null) put("computer-use.mjs", computer.descriptor.piExtension)
                    },
                    environment = buildMap {
                        if (research != null) {
                            put("MAGICPAPER_RESEARCH_MODE", "1")
                            put("MAGICPAPER_RESEARCH_URL", research.url)
                            put("MAGICPAPER_RESEARCH_TOKEN", research.token)
                        }
                        if (agent != null) {
                            put("MAGICPAPER_AGENT_TOOLS_URL", agent.url)
                            put("MAGICPAPER_AGENT_TOOLS_TOKEN", agent.token)
                            put("MAGICPAPER_AGENT_TOOLS_NAMES", JsonArray(toolNames.map(::JsonPrimitive)).toString())
                        }
                        if (questionnaire != null) {
                            put("MAGICPAPER_QUESTIONNAIRE_URL", questionnaire.url)
                            put("MAGICPAPER_QUESTIONNAIRE_TOKEN", questionnaire.token)
                        }
                        if (computer != null) {
                            put("MAGICPAPER_COMPUTER_URL", computer.descriptor.url)
                            put("MAGICPAPER_COMPUTER_TOKEN", computer.descriptor.token)
                        }
                    })
            } catch (failure: Throwable) {
                try { scope.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        /** Never inherit another run's loopback credentials, even if this run has no tools. */
        val environmentKeys: Set<String> = setOf(
            "MAGICPAPER_RESEARCH_MODE", "MAGICPAPER_RESEARCH_URL", "MAGICPAPER_RESEARCH_TOKEN",
            "MAGICPAPER_AGENT_TOOLS_URL", "MAGICPAPER_AGENT_TOOLS_TOKEN", "MAGICPAPER_AGENT_TOOLS_NAMES",
            "MAGICPAPER_QUESTIONNAIRE_URL", "MAGICPAPER_QUESTIONNAIRE_TOKEN",
            "MAGICPAPER_COMPUTER_URL", "MAGICPAPER_COMPUTER_TOKEN",
            "MAGICPAPER_TOKEN_URL", "MAGICPAPER_TOKEN_KEY", "MAGICPAPER_PI_AI",
        )
    }
}

/** Acquisition and teardown are paired even when the next bridge fails to start. */
internal class RunResourceScope : AutoCloseable {
    private val resources = mutableListOf<AutoCloseable>()
    fun <T : AutoCloseable> own(resource: T): T = resource.also { resources += it }
    override fun close() {
        var failure: Throwable? = null
        val closing = resources.asReversed().toList()
        resources.clear()
        closing.forEach { resource ->
            try { resource.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
