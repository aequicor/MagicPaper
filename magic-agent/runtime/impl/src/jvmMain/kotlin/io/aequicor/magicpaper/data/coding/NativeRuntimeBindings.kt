package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.data.llm.asNativeQuestionnaires
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.browser.BrowserSessions
import io.aequicor.magicpaper.domain.checks.CommandChecks
import io.aequicor.magicpaper.logging.AppLog
import java.io.File
import kotlinx.serialization.json.*

/** A selected instance and its declared metadata, constructed by the installed catalog. */
data class NativeRuntimeBinding(val descriptor: BackendAgentDescriptor, val runtime: CodingRuntime)

internal val nativeResources = NativeResources { path -> GenericNativeRuntime::class.java.getResource(path)?.let(::readCodingResource) }
internal val nativeDiagnostics = object : NativeDiagnostics {
    override fun error(component: String, event: String, cause: Throwable, fields: Map<String, String>) = AppLog.error(component, event, cause, fields)
    override fun info(component: String, event: String, fields: Map<String, String>) = AppLog.info(component, event, fields)
}
internal val nativePresentation = NativeToolPresentationResolver { server, tool, arguments ->
    if (server == "magicpaper_computer") NativeToolPresentation("computer",
        io.aequicor.magicpaper.data.computer.ComputerTool.label((arguments as? JsonObject)?.get("action")?.jsonPrimitive?.contentOrNull.orEmpty()))
    else NativeToolPresentation("$server:$tool", "$tool · ${arguments ?: ""}".take(1500))
}

/** Host dependencies are supplied once. The catalog decides which native contributions exist. */
internal class NativeHostEnvironment(
    private val json: Json,
    private val journal: io.aequicor.magicpaper.data.storage.EventJournal,
    private val home: File,
    val library: NativeProviderLibrary,
    private val computer: NativeComputerUse?,
    private val browser: BrowserSessions,
    private val checks: CommandChecks,
    private val questionnaires: RuntimeQuestionnaireFactory,
    private val cachedToken: NativeAuthTokens,
    private val refreshedToken: NativeAuthTokens,
    private val checkSubscription: suspend () -> Unit,
    private val cachedProfile: (LlmProfile) -> LlmProfile = { it },
    private val rootOverride: File? = null,
    private val commandOverride: String? = null,
) {
    private val registries = mutableMapOf<CodingEngine, RuntimeQuestionnaireService>()
    private fun environment(descriptor: BackendAgentDescriptor, paths: NativeBackendPaths): NativeBackendEnvironment {
        val root = rootOverride ?: File(home, paths.home)
        val questionnaireDirectory = if (rootOverride == null) File(home, paths.questionnaires)
            else if (paths.questionnaires.startsWith(paths.home + "/")) File(root, paths.questionnaires.removePrefix(paths.home + "/"))
            else File(root.parentFile, root.name + "-questionnaires")
        val registry = questionnaires.create("${paths.questionnaireScope}:${questionnaireDirectory.absolutePath}",
            FileRuntimeQuestionnaireStore(questionnaireDirectory))
        registries[descriptor.engine] = registry
        return NativeBackendEnvironment(json, root.absolutePath, commandOverride, nativeResources,
            OwnedCodingProcess(File(root, paths.processes)), cachedToken, refreshedToken, registry.asNativeQuestionnaires(),
            nativeDiagnostics, nativePresentation, library, NativeLifecycleJournalAdapter(journal, root.absolutePath))
    }
    private fun binding(agent: BackendAgent) = NativeRuntimeBinding(agent.descriptor,
        GenericNativeRuntime(agent, library, checkSubscription, cachedProfile, computer,
            checkNotNull(registries[agent.descriptor.engine]), browser, checks))
    fun createAll(): List<NativeRuntimeBinding> = backendCatalog.create(::environment).map(::binding)
    fun create(engine: CodingEngine): NativeRuntimeBinding = binding(backendCatalog.create(engine, ::environment))
}

internal fun NativeRuntimeBinding.closeNative() = (runtime as GenericNativeRuntime).agent.close()
