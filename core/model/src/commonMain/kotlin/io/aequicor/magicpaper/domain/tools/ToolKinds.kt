package io.aequicor.magicpaper.domain.tools

import kotlinx.serialization.Serializable

@Serializable enum class ToolRole { ORCHESTRATOR, PLANNER, WORKER, CHAT }
@Serializable enum class ToolCategory { READ, SEARCH, EDIT, EXEC, ACTION }
@Serializable enum class ToolPhase { STARTED, PROGRESS, WAITING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN }
