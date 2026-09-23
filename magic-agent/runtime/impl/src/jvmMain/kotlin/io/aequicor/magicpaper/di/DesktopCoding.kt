package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.data.coding.*
import io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.browser.BrowserSessions
import io.aequicor.magicpaper.domain.checks.CommandChecks
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** App composition sees one owner; native engine contributions are discovered inside backend-agents. */
class DesktopNativeRuntime internal constructor(
    val runtime: CodingRuntime,
    val subscription: OpenAiSubscriptionService,
    val modelLimits: ModelLimitCatalog?,
    private val nativeResources: List<AutoCloseable>,
    private val agents: List<BackendAgent>,
    private val library: NativeProviderLibrary,
    private val checks: CommandChecks,
) : AutoCloseable {
    suspend fun prepareForReset(discardUnresolvable: Boolean = false) {
        // These fences fail only after everything they could stop is stopped; the reset erases the unproven records.
        suspend fun consented(action: suspend () -> Unit) = try { action() } catch (unconfirmed: NativeCleanupUnconfirmed) {
            if (!discardUnresolvable) throw unconfirmed
            AppLog.info("coding", "native.cleanup.discarded", mapOf("result" to "user_consent"))
        }
        finishNativeCleanup(agents.map { agent -> suspend { consented { agent.prepareForReset() } } } +
            listOf(suspend { checks.prepareForReset() }, suspend { consented { library.prepareForReset() } }))
    }
    suspend fun resumeAfterReset() = finishNativeCleanup(
        listOf(suspend { library.resumeAfterReset() }, suspend { checks.resumeAfterReset() }) + agents.map { agent -> suspend { agent.resumeAfterReset() } })
    suspend fun eraseSessionsForReset() = finishNativeCleanup(agents.map { agent -> suspend { agent.eraseSessionsForReset() } })
    suspend fun shutdown() = finishNativeCleanup(
        agents.map { agent -> suspend { agent.shutdown() } } + listOf(suspend { checks.close() }, suspend { library.shutdown() }, suspend { close() }))

    private suspend fun finishNativeCleanup(actions: List<suspend () -> Unit>) {
        val failures = mutableListOf<Throwable>()
        withContext(NonCancellable) {
            actions.forEach { action -> try { action() } catch (failure: Throwable) { failures += failure } }
        }
        try { currentCoroutineContext().ensureActive() } catch (failure: CancellationException) { failures += failure }
        val primary = failures.firstOrNull { it is CancellationException } ?: failures.firstOrNull() ?: return
        failures.filter { it !== primary }.forEach(primary::addSuppressed)
        throw primary
    }
    override fun close() {
        var failure: Throwable? = null
        try { runtime.abortAll() } catch (error: Throwable) { failure = error }
        (nativeResources + AutoCloseable { subscription.close() }).forEach { resource -> try { resource.close() } catch (error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        } }
        failure?.let { throw it }
    }
}

fun createDesktopNativeRuntime(
    json: kotlinx.serialization.json.Json,
    journal: io.aequicor.magicpaper.data.storage.EventJournal,
    computer: NativeComputerUse,
    secrets: io.aequicor.magicpaper.data.storage.SecretStore,
    questionnaireFactory: RuntimeQuestionnaireFactory,
    skillSelection: suspend (String) -> CodingSkillSelection,
    recordSkillRun: suspend (CodingSkillRunRecord) -> Unit,
    runObserver: CodingRunObserver,
    browser: BrowserSessions,
    checks: CommandChecks,
): DesktopNativeRuntime {
    val home = File(System.getProperty("user.home"), ".MagicPaper")
    val library = createNativeProviderLibrary(File(home, "coding").absolutePath, nativeResources,
        OwnedCodingProcess(File(home, "coding/provider-processes")), nativeDiagnostics, NativeLifecycleJournalAdapter(journal, File(home, "coding/provider-processes").absolutePath))
    val owned = mutableListOf<AutoCloseable>(library)
    try {
        val subscription = CodexAppServerOpenAiSubscription(json, computerUse = computer, browser = browser, checks = checks,
            secretStore = secrets, questionnaireFactory = questionnaireFactory, sharedProviderLibrary = library, journal = journal)
        owned += subscription
        val bindings = NativeHostEnvironment(json, journal, home, library, computer, browser, checks, questionnaireFactory,
            NativeAuthTokens { subscription.cachedAccessToken() }, NativeAuthTokens { subscription.subscriptionAccessToken() },
            { check(subscription.account().signedIn) { "Войдите в ChatGPT в настройках движков" } },
            subscription::withCachedContextWindow).createAll()
        bindings.forEach { owned += AutoCloseable { it.closeNative() } }
        return DesktopNativeRuntime(DesktopCodingRuntime(bindings, computer, skillSelection, checks, recordSkillRun, runObserver,
            workspaceRootPath = File(home, "coding").absolutePath),
            subscription, engineModelLimits(), bindings.map { AutoCloseable { it.closeNative() } } + library, bindings.map { (it.runtime as GenericNativeRuntime).agent }, library, checks)
    } catch (failure: Throwable) {
        owned.asReversed().forEach { resource -> try { resource.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) } }
        throw failure
    }
}
