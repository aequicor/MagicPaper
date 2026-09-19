package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.tools.CustomOrchestration
import io.aequicor.magicpaper.domain.tools.OrchestrationActions
import io.aequicor.magicpaper.plugins.MagicPlugin
import io.aequicor.magicpaper.plugins.builtin.CodingPlanningPlugin
import io.aequicor.magicpaper.ui.CodingComponent
import io.aequicor.magicpaper.ui.CodingService
import io.aequicor.magicpaper.ui.DefaultCodingComponentFactory
import io.aequicor.magicpaper.ui.DefaultCodingService
import io.aequicor.magicpaper.ui.components.CodingPresentation
import io.aequicor.magicpaper.ui.components.DefaultCodingPresentation

/**
 * Assembles the coding half of a session for a host that can run an agent.
 *
 * The application shell passes what it owns and receives one contract back, so no coding
 * implementation type is named in common code. Ownership inside is unchanged: the graph still
 * owns the runtime collaborators, the service still owns drafts and background work.
 */
fun codingFeature(deps: CodingFeatureDependencies): CodingFeature {
    val projects = codingProjectRepository(deps.store, deps.json, deps.settings, deps.profiles, deps.codingRuntime)
    val graph = CodingRuntimeGraph(deps.store, deps.json, deps.settings, deps.profiles, projects, deps.codingRuntime,
        deps.planningWorkspace, deps.integrationChecks, deps.usage, deps.gateway, deps.search,
        draftRepository = deps.drafts, taskWorkspace = deps.taskWorkspace,
        sourceAccess = ResearchSourceAccess(deps.readResearchPage))
    val runtime = graph.runtime ?: deps.codingRuntime
    graph.toolHost.mediaGeneration = deps.media
    graph.toolHost.mediaAllowed = { context, kind -> mediaAllowed(deps, projects, context, kind) }
    val service = DefaultCodingService(deps.settings, deps.profiles, deps.store, deps.json, runtime, projects,
        deps.dirPicker, deps.gateway, graph.planningChat, deps.requestPins, deps.usage,
        onOpenSession = deps.onOpenSession,
        draftRepository = deps.drafts, draftBlobs = deps.draftBlobs,
        taskWorktrees = graph.taskWorktrees,
        mediaGeneration = deps.media,
        removePluginDrafts = deps.removePluginDrafts)
    val plugin = CodingPlanningPlugin(graph.planningStore, graph.planComposer, deps.dossier,
        graph.planningExecution, runtime, projects, deps.profiles, deps.settings,
        draftRepository = deps.drafts, applicationScope = deps.applicationScope)
    return object : CodingFeature {
        override val projects: CodingProjectRepository = projects
        override val planning: PlanningRepository = graph.planningStore
        override val runtime: CodingRuntime = runtime
        override val service: CodingService = service
        override val componentFactory: CodingComponent.Factory =
            DefaultCodingComponentFactory(service, deps.filePicker, deps.projectSkills)
        override val presentation: CodingPresentation = DefaultCodingPresentation
        override val plugins: List<MagicPlugin> = listOf(plugin)
        // Only an engine that supports this host can answer chat; otherwise chat stays on HTTP.
        override val chatBackend: ChatBackend? = runtime.takeIf { it.supported }
        override val onMediaTerminal: suspend (MediaGenerationOwner, String, GeneratedMedia) -> Unit =
            graph.toolHost::reconcileMediaCompletion
        override val orchestrationActions = OrchestrationActions { context, operation, tool, arguments ->
            graph.toolHost.receiver(context, operation, tool, arguments)
        }
        override fun bindOrchestration(orchestration: CustomOrchestration) { graph.toolHost.orchestration = orchestration }
        override suspend fun start() = graph.start()
        override suspend fun close() = graph.close()
        override suspend fun prepareForReset() = service.prepareForReset()
        override suspend fun pauseForReset() = graph.pauseForReset()
        override suspend fun clearForReset() { projects.wipe(); graph.clearForReset() }
        override suspend fun resumeAfterReset() = graph.resumeAfterReset()
    }
}

/**
 * A research chat owns its media policy through the conversation that started it; a project
 * session owns it through the session record. The two are not one predicate: the chat branch
 * follows [ChatSession.researchParentId] up to the conversation the user actually configured.
 */
private suspend fun mediaAllowed(
    deps: CodingFeatureDependencies,
    projects: CodingProjectRepository,
    context: io.aequicor.magicpaper.domain.tools.ToolExecutionContext,
    kind: MediaKind,
): Boolean = if (context.projectId == "chat-${context.ownerSessionId}") {
    val session = deps.chats.session(context.ownerSessionId)
    val parentId = session?.researchParentId
    val owner = if (parentId != null) deps.chats.session(parentId) else session
    owner?.mediaTools?.enabled(kind) == true
} else projects.sessions(context.projectId).firstOrNull { it.id == context.ownerSessionId }?.mediaTools?.enabled(kind) == true
