package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.browser.BrowserSessions
import io.aequicor.magicpaper.domain.checks.CommandChecks
import io.aequicor.magicpaper.domain.tools.ToolSession
import io.aequicor.magicpaper.logging.AppLog
import java.io.File
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Explicit invocation identity; no new serialized fields or global run registry. */
internal class NativeRunContext(val requestId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NativeRunContext>
}

/** The only application adapter for native execution. All engine distinctions are declared by its port. */
internal class GenericNativeRuntime(
    val agent: BackendAgent,
    private val library: NativeProviderLibrary,
    private val checkSubscription: suspend () -> Unit,
    private val cachedProfile: (LlmProfile) -> LlmProfile,
    override val computerUse: NativeComputerUse?,
    private val registry: RuntimeQuestionnaireService,
    private val browser: BrowserSessions,
    private val checks: CommandChecks,
) : CodingRuntime {
    override val recovery = object : NativeRunRecovery {
        override suspend fun inspect(sessionId: String) = agent.inspectRecovery(sessionId).toRuntime()
        override suspend fun stop(ref: NativeRunRecoveryRef) = agent.stopRecovery(ref.toNative()).toRuntime()
        override suspend fun acknowledge(ref: NativeRunRecoveryRef, parentDecisionId: String) =
            agent.acknowledgeRecovery(ref.toNative(), parentDecisionId).toRuntime()
        override suspend fun acknowledgeNoDispatch(proof: NativeRunNoDispatchProof, parentDecisionId: String) =
            agent.acknowledgeNoDispatch(proof.toNative(), parentDecisionId).toRuntime()
    }
    private fun NativeRunRecoveryRef.toNative(): NativeAttemptRef {
        require(engine == agent.descriptor.engine) { "Recovery belongs to another native owner" }
        return NativeAttemptRef(NativeRunRef(sessionId, requestId), attempt)
    }
    private fun NativeAttemptRef.toRuntime() = NativeRunRecoveryRef(agent.descriptor.engine, run.sessionId, run.requestId, ordinal)
    private fun NativeRecoveryAcknowledgement.toRuntime() = NativeRunRecoveryAcknowledgement(id, predecessor.toRuntime(), parentDecisionId)
    private fun NativeRunRecoveryAcknowledgement.toNative() = NativeRecoveryAcknowledgement(id, predecessor.toNative(), parentDecisionId)
    private fun NativeNoDispatchProof.toRuntime() = NativeRunNoDispatchProof(agent.descriptor.engine, run.sessionId, run.requestId, proofId, journalGeneration)
    private fun NativeRunNoDispatchProof.toNative(): NativeNoDispatchProof {
        require(engine == agent.descriptor.engine) { "Recovery belongs to another native owner" }
        return NativeNoDispatchProof(NativeRunRef(sessionId, requestId), proofId, journalGeneration)
    }
    private fun NativeNoDispatchAcknowledgement.toRuntime() = NativeRunNoDispatchAcknowledgement(id, proof.toRuntime(), parentDecisionId)
    private fun NativeRunNoDispatchAcknowledgement.toNative() = NativeNoDispatchAcknowledgement(id, proof.toNative(), parentDecisionId)
    private fun NativeRecoverySummary.toRuntime() = NativeRunRecoverySnapshot(items.map {
        NativeRunRecoveryItem(it.attempt.toRuntime(), NativeRunOutcome.valueOf(it.outcome.name), NativeRunTermination.valueOf(it.termination.name), it.acknowledgement?.toRuntime())
    }, persistenceUnknown, noDispatch.map { NativeRunNoDispatchItem(it.proof.toRuntime(), it.acknowledgement?.toRuntime()) },
        consumptions.map { NativeRunRecoveryConsumption(it.acknowledgementId, agent.descriptor.engine, it.run.sessionId, it.run.requestId) })
    override var globalFeatureFlags = FeatureFlagState()
    override val supported = true
    override val rootPath get() = agent.rootPath
    override val questionnaires = registry.requests
    override val approvals = agent.approvals?.requests ?: MutableStateFlow(emptyList())
    override val modelSources: Map<CodingEngine, CodingModelSource> = agent.models
        ?.let { catalog -> mapOf(agent.descriptor.engine to CodingModelSource { catalog.models() }) }
        ?: emptyMap()
    override suspend fun respondQuestionnaire(id: String, answers: List<PlanningAnswer>) = registry.respond(id, answers)
    override suspend fun respondApproval(id: String, decision: CodingApprovalDecision) =
        checkNotNull(agent.approvals) { "Native approvals are not supported" }.respond(id, decision)
    override suspend fun status() = agent.status().runtimeStatus()
    override fun ensureReady() = agent.prepare().map { it.runtimeStatus() }
    override suspend fun uninstall() = checkNotNull(agent.removal) { "Dependencies are managed by an external installer" }.remove()
    override suspend fun nativeToolResults(session: CodingSession, callIds: Set<String>) =
        checkNotNull(agent.history) { "Native history is not supported" }.results(session.piSessionId, callIds)
    override suspend fun reconcile(sessionId: String) {
        try { agent.reconcile(sessionId) }
        catch (unknown: NativeRecoveryRequired) { throw NativeRunRecoveryRequired(unknown.recovery.toRuntime(), unknown) }
    }
    override fun abort(sessionId: String) {
        checks.abort(sessionId)
        computerUse?.disable(sessionId)
        agent.abort(sessionId)
    }
    override fun abortAll() { checks.abortAll(); computerUse?.disable(); agent.abortAll() }

    override suspend fun preflight(profile: LlmProfile) {
        if (profile.provider == ProviderType.OPENAI_SUBSCRIPTION) {
            checkSubscription()
        }
        checkReady(agent.prepare().last())
        if (agent.modelConnection(profile) == NativeModelConnectionKind.RESPONSES_PROXY) checkReady(library.prepare().last())
    }

    override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) =
        runSession(project, session.forPendingRun().also { require(!it.planningMode) { "Используйте защищённый маршрут планирования" } },
            prompt, checkNotNull(profile), attachments, if (session.researchMode) CodingInteractionMode.RESEARCH else CodingInteractionMode.CODE)

    override fun runPlanning(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile) =
        runSession(project, session.copy(piSessionId = ""), prompt, profile, emptyList(), CodingInteractionMode.PLANNING)

    private fun runSession(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile,
        attachments: List<Attachment>, mode: CodingInteractionMode): Flow<CodingEvent> = flow { coroutineScope {
        var terminalObserved = false
        val latency = CodingRunLatency()
        val latencyFields = mapOf("projectId" to project.id, "sessionId" to session.id, "engine" to agent.descriptor.engine.name)
        try {
            check(File(project.path).isDirectory) { "Папка проекта недоступна" }
            require(profile.configured) { "Не настроено подключение модели" }
            val planning = mode == CodingInteractionMode.PLANNING
            val research = mode == CodingInteractionMode.RESEARCH
            val requestId = currentCoroutineContext()[NativeRunContext]?.requestId
                ?: session.pendingRun?.runId ?: UUID.randomUUID().toString()
            val flags = session.featureFlags.resolve(globalFeatureFlags)
            val speedBoost = flags.isEnabled(FeatureFlag.AGENT_SPEED_BOOST)
            val model = agent.modelProfile(cachedProfile(if (planning) profile.forModel() else profile.forCoding()), mode, speedBoost)
            val tools = currentCoroutineContext()[ToolSession]
            // This scope consumes the lease already bound to requestId by DesktopCodingRuntime.
            AgentRunResources.prepare(project, session, planning, computerUse, registry, browser, checks, tools,
                cacheToolDefinitions = speedBoost, requestId = requestId).use { resources ->
                val parameters = ProviderControls.parameters(model)
                val bridge = if (agent.modelConnection(model) == NativeModelConnectionKind.RESPONSES_PROXY)
                    library.bridge(model, parameters) else null
                bridge.use {
                    coroutineScope {
                        val tracking = currentCoroutineContext()[RuntimeUsageContext]
                        val metrics = bridge?.let { endpoint -> launch {
                            endpoint.usage.collect { metric -> tracking?.ledger?.record(tracking.observation, UsageRecord("bridge:${metric.id}",
                                scope = tracking.owner, provider = model.provider.name, model = model.modelId,
                                tokens = metric.usage.tokens,
                                cost = model.modelCatalog.firstOrNull { it.id == model.modelId }?.pricing?.estimate(metric.usage.tokens),
                                completed = metric.completed)) }
                        } }
                        var primary: Throwable? = null
                        try {
                            val request = NativeAgentRequest(requestId, session, project.path, prompt,
                                codingSystemPrompt(agent.descriptor.engine, planning, model.advanced.systemPromptOverride,
                                    research, session.runtimePlanningRules, flags, session, browserAvailable = browser.available),
                                if (planning) PLANNING_INSTRUCTIONS else if (research) RESEARCH_INSTRUCTIONS else null,
                                model, parameters, attachments, mode, resources.nativeTools(), bridge?.connection,
                                imageInput = computerUse?.grant(session.id) != null,
                                // Replace ephemeral endpoint credentials on resume, including an explicit revoked endpoint.
                                freshResume = research || computerUse != null,
                                maxOutputContinuations = if (speedBoost) 3 else 2,
                                recovery = currentCoroutineContext()[NativeRunRecoveryBinding]?.acknowledgement?.toNative(),
                                noDispatchRecovery = currentCoroutineContext()[NativeRunRecoveryBinding]?.noDispatchAcknowledgement?.toNative())
                            agent.run(request).collect { event ->
                                latency.apply(event).forEach { CodingLatencyDiagnostics.log(it, latencyFields) }
                                if (event is CodingEvent.Finished) terminalObserved = true
                                else emit(if (event is CodingEvent.UsageObserved && (planning || bridge != null)) event.copy(accounting = false) else event)
                            }
                        } catch (failure: Throwable) {
                            primary = failure
                            throw failure
                        } finally {
                            // Close before joining a potentially blocked metric reader. use() owns the final idempotent close too.
                            withContext(NonCancellable) {
                                var cleanup: Throwable? = null
                                try { bridge?.shutdown() } catch (failure: Throwable) { cleanup = failure }
                                try { metrics?.cancelAndJoin() } catch (failure: Throwable) {
                                    if (cleanup == null) cleanup = failure else cleanup.addSuppressed(failure)
                                }
                                cleanup?.let { failure -> if (primary == null) throw failure else primary.addSuppressed(failure) }
                            }
                        }
                    }
                }
            }
            check(terminalObserved) { "Native adapter completed without terminal evidence" }
            val (lateCalls, timing) = latency.finish()
            lateCalls.forEach { CodingLatencyDiagnostics.log(it, latencyFields) }
            CodingLatencyDiagnostics.log(timing, latencyFields)
            if (timing.wallMs >= 5_000) emit(CodingEvent.Notice(timing.describe()))
            emit(CodingEvent.Finished)
        } catch (cancelled: CancellationException) {
            try { agent.abort(session.id) } catch (cleanup: Throwable) {
                // Coroutine stack recovery may copy a caught cancellation; a fresh causal wrapper retains cleanup evidence.
                throw CancellationException("Native run cancelled during cleanup").apply {
                    initCause(cancelled); addSuppressed(cleanup)
                }
            }
            throw cancelled
        } catch (unknown: NativeRecoveryRequired) {
            // Do not flatten Unknown into Failed+Finished: the parent must keep its recovery fence.
            throw NativeRunRecoveryRequired(unknown.recovery.toRuntime(), unknown)
        } catch (failure: Throwable) {
            AppLog.error("native_runtime", "run_failed", mapOf("sessionId" to session.id,
                "failure" to failure::class.simpleName.orEmpty()))
            emit(CodingEvent.Failed("Не удалось выполнить запрос. Проверьте подключение и состояние движка, затем продолжите сессию."))
            emit(CodingEvent.Finished)
        }
    } }.flowOnPreservingOutput(Dispatchers.IO)

    private fun checkReady(status: NativeInstallationStatus) {
        check(status.phase == NativeInstallationPhase.READY) { status.detail }
    }
    private fun NativeInstallationStatus.runtimeStatus() = RuntimeStatus(when (phase) {
        NativeInstallationPhase.CHECKING -> RuntimePhase.CHECKING
        NativeInstallationPhase.INSTALLING -> RuntimePhase.INSTALLING
        NativeInstallationPhase.READY -> RuntimePhase.READY
        NativeInstallationPhase.ERROR -> RuntimePhase.ERROR
    }, detail, version)
}
