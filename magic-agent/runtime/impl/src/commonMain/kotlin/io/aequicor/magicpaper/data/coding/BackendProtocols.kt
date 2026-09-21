package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.backend.createBackendAgentProtocols

/** Immutable wire-format adapters, not a registry of application services or running sessions. */
internal val backendProtocols = createBackendAgentProtocols()

/** Installed metadata only; a catalog never holds live native instances. */
internal val backendCatalog = io.aequicor.magicpaper.backend.createBackendAgentCatalog()
