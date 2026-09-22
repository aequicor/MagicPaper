package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** Service registration and native lifecycle only; application tools were prepared by the caller. */
class CodexBackendContribution : BackendAgentContribution {
    override val descriptor = CodexNativeAdapter().descriptor
    override val paths = NativeBackendPaths("codex", "coding-processes", "codex/questionnaires", "native-codex")
    override fun create(environment: NativeBackendEnvironment): NativeAgentAdapter = CodexBackendAgent(environment, descriptor)
    override fun createSubscription(environment: NativeSubscriptionEnvironment): NativeSubscriptionAccess =
        CodexNativeAdapter().client(environment.json, environment.home, environment.commandOverride, environment.processes,
            environment.accessTokens, environment.questionnaires, environment.diagnostics, environment.toolPresentation)
}

internal class CodexBackendAgent(
    private val environment: NativeBackendEnvironment,
    override val descriptor: BackendAgentDescriptor,
    private val createClient: () -> CodexClient = {
        CodexNativeAdapter().client(environment.json, environment.home, environment.commandOverride ?: System.getenv("MAGICPAPER_CODEX_PATH"),
            environment.processes, environment.cachedAccessTokens, environment.questionnaires, environment.diagnostics,
            environment.toolPresentation)
    },
) : NativeAgentAdapter {
    private fun client() = createClient()
    private val lifecycleLock = Any()
    private var closed = false
    private val control = client()
    private val clients = ConcurrentHashMap<String, CodexClient>()
    private val pending = MutableStateFlow<List<CodingApproval>>(emptyList())
    override val rootPath = environment.home
    override val approvals = object : NativeApprovalRequests {
        override val requests = pending.asStateFlow()
        override suspend fun respond(id: String, decision: CodingApprovalDecision) {
            val owner = clients.values.singleOrNull { it.codingApprovals.value.any { approval -> approval.id == id } }
                ?: error("Approval request is no longer active")
            owner.respondCodingApproval(id, decision)
        }
    }
    override val history = NativeToolHistory { id, calls -> control.readCodingToolResults(id, calls) }
    override val removal: NativeRemoval? = null
    override val models = NativeModelCatalog { control.codingModels() }

    override suspend fun status() = control.runtimeStatus().let {
        NativeInstallationStatus(if (it.ready) NativeInstallationPhase.READY else NativeInstallationPhase.ERROR, it.detail)
    }
    override fun prepare() = flow { emit(status()) }
    override fun modelProfile(profile: LlmProfile, mode: CodingInteractionMode, speedBoost: Boolean) = profile
    override fun modelConnection(profile: LlmProfile) = if (profile.provider == ProviderType.OPENAI_SUBSCRIPTION)
        NativeModelConnectionKind.DIRECT else NativeModelConnectionKind.RESPONSES_PROXY

    override fun run(request: NativeAgentRequest): Flow<CodingEvent> = flow {
        coroutineScope {
            val native = synchronized(lifecycleLock) {
                check(!closed) { "Native agent is closed" }
                check(!clients.containsKey(request.session.id)) { "Session is already running" }
                client().also { clients[request.session.id] = it }
            }
            var runFailure: Throwable? = null
            try {
                run {
                    coroutineScope {
                        val observer = launch { native.codingApprovals.collect { current ->
                            pending.update { old -> old.filterNot { it.sessionId == request.session.id } + current }
                        } }
                        var primary: Throwable? = null
                        try {
                            val connection = request.modelConnection
                            require((modelConnection(request.profile) == NativeModelConnectionKind.RESPONSES_PROXY) == (connection != null)) {
                                "Provider connection does not match the prepared native request"
                            }
                            val launch = codexLaunchModel(request.session, request.profile,
                                direct = modelConnection(request.profile) == NativeModelConnectionKind.DIRECT)
                            val payload = CodexRunRequest(request.workingDirectory, launch.modelId,
                                connection?.providerId ?: "openai", connection?.configuration() ?: JsonObject(emptyMap()),
                                request.tools.mcpConfiguration, request.instructions, input(request.prompt, request.attachments),
                                request.mode, launch.effort,
                                request.session.piSessionId.takeIf { request.mode != CodingInteractionMode.PLANNING && it.isNotBlank() },
                                when (request.mode) {
                                    CodingInteractionMode.PLANNING -> "MagicPaper Planning"
                                    CodingInteractionMode.RESEARCH -> "MagicPaper Research"
                                    CodingInteractionMode.CODE -> "MagicPaper Coding"
                                }, request.baseInstructions)
                            native.runCoding(request.session, payload, request.freshResume).collect { emit(it) }
                        } catch (failure: Throwable) {
                            primary = failure
                            if (failure is CancellationException) {
                                try { native.abortCoding(request.session.id) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                            }
                            throw failure
                        } finally {
                            withContext(NonCancellable) {
                                var cleanupFailure: Throwable? = null
                                try { observer.cancelAndJoin() } catch (cleanup: Throwable) { cleanupFailure = cleanup }
                                try { if (request.mode == CodingInteractionMode.PLANNING) native.abortCoding(request.session.id) }
                                catch (cleanup: Throwable) { if (cleanupFailure == null) cleanupFailure = cleanup else cleanupFailure.addSuppressed(cleanup) }
                                cleanupFailure?.let { if (primary == null) throw it else primary.addSuppressed(it) }
                            }
                        }

                    }
                }
            } catch (failure: Throwable) { runFailure = failure; throw failure }
            finally {
                try { withContext(NonCancellable) { native.shutdownCoding() } }
                catch (cleanup: Throwable) {
                    if (runFailure == null) throw cleanup
                    if (runFailure is CancellationException) throw CancellationException("Native cancellation cleanup failed").apply {
                        initCause(runFailure); addSuppressed(cleanup)
                    }
                    runFailure.addSuppressed(cleanup)
                } finally {
                    clients.remove(request.session.id, native)
                    pending.update { old -> old.filterNot { it.sessionId == request.session.id } }
                }
            }
        }
    }

    override suspend fun reconcile(sessionId: String): Boolean {
        // Codex reconciliation does not yet independently prove descendant termination.
        (clients[sessionId] ?: control).reconcileCoding(sessionId)
        return false
    }
    override fun abort(sessionId: String) { clients[sessionId]?.abortCoding(sessionId) }
    override fun abortAll() { clients.forEach { (id, client) -> client.abortCoding(id) } }
    override fun close() {
        var failure: Throwable? = null
        synchronized(lifecycleLock) { closed = true; clients.values.toList() + control }.forEach { client -> try { client.close() } catch (cleanup: Throwable) {
            if (failure == null) failure = cleanup else failure!!.addSuppressed(cleanup)
        } }
        failure?.let { throw it }
    }

    private fun NativeModelConnection.configuration() = buildJsonObject {
        put("model_provider", providerId)
        putJsonObject("model_providers.$providerId") {
            put("name", "MagicPaper API"); put("base_url", baseUrl); put("wire_api", "responses")
            put("experimental_bearer_token", bearerToken); put("requires_openai_auth", false); put("supports_websockets", false)
        }
        put("web_search", "disabled")
    }

    private fun input(prompt: String, attachments: List<Attachment>) = buildJsonArray {
        add(buildJsonObject {
            put("type", "text")
            put("text", buildString {
                append(prompt)
                attachments.filter { it.kind == AttachmentKind.TEXT }.forEach {
                    append("\n\nВложение ").append(it.name).append(":\n").append(it.decodeText())
                }
                attachments.filter { it.kind == AttachmentKind.FILE }.takeIf { it.isNotEmpty() }?.let {
                    append("\n\nБинарные вложения не переданы: ").append(it.joinToString { attachment -> attachment.name })
                }
            })
        })
        attachments.filter { it.kind == AttachmentKind.IMAGE }.forEach {
            add(buildJsonObject { put("type", "image"); put("url", "data:${it.mimeType};base64,${it.dataBase64}") })
        }
    }
}
