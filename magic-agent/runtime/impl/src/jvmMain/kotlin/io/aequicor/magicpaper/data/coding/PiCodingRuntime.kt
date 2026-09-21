package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.browser.BrowserSessions
import io.aequicor.magicpaper.domain.checks.CommandChecks
import java.io.File
import kotlinx.serialization.json.Json

/** Compatibility entry for native integration fixtures. Execution is the same generic host used by the catalog. */
class PiCodingRuntime private constructor(
    val binding: NativeRuntimeBinding,
    private val library: NativeProviderLibrary,
) : CodingRuntime by binding.runtime, AutoCloseable {
    private constructor(pair: Pair<NativeRuntimeBinding, NativeProviderLibrary>) : this(pair.first, pair.second)
    constructor(
        rootDir: File = File(File(System.getProperty("user.home"), ".MagicPaper"), "coding"),
        computerUse: NativeComputerUse? = null,
        subscriptionToken: (suspend () -> String)? = null,
        browser: BrowserSessions,
        checks: CommandChecks,
        questionnaireFactory: RuntimeQuestionnaireFactory,
        journal: io.aequicor.magicpaper.data.storage.EventJournal,
    ) : this(create(rootDir, computerUse, subscriptionToken, browser, checks, questionnaireFactory, journal))

    override fun close() {
        var failure: Throwable? = null
        try { binding.closeNative() } catch (error: Throwable) { failure = error }
        try { library.close() } catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
        failure?.let { throw it }
    }
    companion object {
        private fun create(root: File, computer: NativeComputerUse?, token: (suspend () -> String)?,
            browser: BrowserSessions, checks: CommandChecks, questionnaires: RuntimeQuestionnaireFactory, journal: io.aequicor.magicpaper.data.storage.EventJournal)
            : Pair<NativeRuntimeBinding, NativeProviderLibrary> {
            val library = createNativeProviderLibrary(root.absolutePath, nativeResources,
                OwnedCodingProcess(File(root, "provider-processes")), nativeDiagnostics, NativeLifecycleJournalAdapter(journal, File(root, "provider-processes").absolutePath))
            try {
                val tokens = NativeAuthTokens { checkNotNull(token) { "Subscription token provider is unavailable" }.invoke() }
                val host = NativeHostEnvironment(Json, journal, root.absoluteFile.parentFile, library, computer, browser, checks,
                    questionnaires, NativeAuthTokens { null }, tokens, { tokens.readAccessToken() }, rootOverride = root)
                return host.create(backendCatalog.descriptor(io.aequicor.magicpaper.domain.CodingEngine.PI).engine) to library
            } catch (failure: Throwable) {
                try { library.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }; throw failure
            }
        }
    }
}
