package io.aequicor.magicpaper.backend

import java.util.ServiceLoader

fun createBackendAgentCatalog(): BackendAgentCatalog = createBackendAgentCatalog(
    ServiceLoader.load(BackendAgentContribution::class.java, BackendAgentContribution::class.java.classLoader).toList())

/** Explicit application contribution injection; each adapter still crosses durable native admission. */
fun createBackendAgentCatalog(contributions: List<BackendAgentContribution>): BackendAgentCatalog = BackendAgentCatalog(
    contributions,
    BackendAgentConstructor { contribution, environment -> io.aequicor.magicpaper.backend.lifecycle.journaledBackendAgent(contribution, environment) })

fun createNativeProviderLibrary(home: String, resources: NativeResources, ownership: NativeProviderProcesses,
    diagnostics: NativeDiagnostics, journal: NativeLifecycleJournal, installation: PiInstallation? = null): NativeProviderLibrary =
    io.aequicor.magicpaper.data.coding.PiNativeProviderLibrary(java.io.File(home), resources, ownership, diagnostics,
        io.aequicor.magicpaper.backend.lifecycle.NativeLifecycleOwner(journal, diagnostics), installation)
