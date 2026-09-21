package io.aequicor.magicpaper.backend

import io.aequicor.magicpaper.domain.CodingEngine

/** Native execution capabilities belong to adapters; persisted identities remain in core:model. */
enum class BackendAgentCapability {
    MANAGED_INSTALLATION,
    EXTERNAL_INSTALLATION,
    NATIVE_APPROVALS,
    NATIVE_TOOL_HISTORY,
    /** Движок сам перечисляет свои модели и их уровни thinking: [NativeAgentAdapter.models] задан. */
    NATIVE_MODEL_CATALOG,
    DISABLE_NATIVE_SKILL_LOADING,
}

data class BackendAgentDescriptor(
    val engine: CodingEngine,
    val adapterName: String,
    val capabilities: Set<BackendAgentCapability>,
    val summary: String,
    val providerSummary: String,
    val skillLoadingSummary: String,
    val fileToolInstructions: String,
    val codingInstructions: String = "",
)

/** Durable identity is recorded before stdin can deliver an instruction to the native process. */
interface NativeProcessOwnership {
    fun record(id: String, process: Process, attachLifetime: Boolean = true)
    fun clear(id: String)
}
