package io.aequicor.magicpaper.backend

import io.aequicor.magicpaper.data.coding.PiNativeAdapter
import io.aequicor.magicpaper.data.llm.CodexNativeAdapter

/** The complete list of implemented protocols lives inside backend-agents. */
fun createBackendAgentProtocols(): BackendAgentProtocols =
    BackendAgentProtocols(PiNativeAdapter(), CodexNativeAdapter())
