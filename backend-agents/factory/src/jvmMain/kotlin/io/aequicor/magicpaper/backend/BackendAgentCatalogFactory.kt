package io.aequicor.magicpaper.backend

import io.aequicor.magicpaper.domain.CodingEngine
import java.util.ServiceLoader

private fun installedContributions(): List<BackendAgentContribution> =
    ServiceLoader.load(BackendAgentContribution::class.java, BackendAgentContribution::class.java.classLoader).toList()

fun createBackendAgentCatalog(): BackendAgentCatalog = createBackendAgentCatalog(installedContributions())

/** Account access of one installed engine, or null when it offers none. Each call owns a new connection; the caller closes it. */
fun createNativeSubscriptionAccess(engine: CodingEngine, environment: NativeSubscriptionEnvironment): NativeSubscriptionAccess? =
    installedContributions().single { it.descriptor.engine == engine }.createSubscription(environment)

/** Explicit application contribution injection; each adapter still crosses durable native admission. */
fun createBackendAgentCatalog(contributions: List<BackendAgentContribution>): BackendAgentCatalog = BackendAgentCatalog(
    contributions,
    BackendAgentConstructor { contribution, environment -> io.aequicor.magicpaper.backend.lifecycle.journaledBackendAgent(contribution, environment) })

fun createNativeProviderLibrary(home: String, resources: NativeResources, ownership: NativeProviderProcesses,
    diagnostics: NativeDiagnostics, journal: NativeLifecycleJournal): NativeProviderLibrary =
    io.aequicor.magicpaper.data.coding.PiNativeProviderLibrary(java.io.File(home), resources, ownership, diagnostics,
        io.aequicor.magicpaper.backend.lifecycle.NativeLifecycleOwner(journal, diagnostics))
