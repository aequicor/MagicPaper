package io.aequicor.magicpaper.data.llm

import kotlinx.serialization.json.*

/** Planning never inherits coding write roots or approval escalation. */
internal object CodexPlanningPermissions {
    fun JsonObjectBuilder.approvals() { put("approvalPolicy", "never") }
    fun sandboxPolicy() = buildJsonObject { put("type", "readOnly") }
    fun threadConfig() = buildJsonObject {
        put("sandbox_mode", "read-only")
        put("approval_policy", "never")
        put("mcp_servers", buildJsonObject {})
    }
}
