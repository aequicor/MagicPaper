package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Application-lifetime OS owner. Acquiring authority always follows an explicit native request. */
interface NativeComputerUse : ComputerUse, AutoCloseable {
    /** Also binds an already manually enabled grant to this exact native request. */
    suspend fun begin(sessionId: String, requestId: String): ComputerLease?
    fun grant(sessionId: String): ComputerLease?
    /** Immediate revocation; stale releases cannot revoke a later lease. */
    fun release(lease: ComputerLease)
    /** Consumes an existing, durably bound lease; it never acquires authority. */
    fun endpoint(lease: ComputerLease, requestId: String): ComputerEndpoint
    /** After native producers are joined; revokes and joins the persistence interpreter. */
    suspend fun prepareForReset()
    /** Called only after the application journal has been cleared. Does not acquire authority. */
    suspend fun resumeAfterReset()
}

@Serializable data class ComputerLease(val sessionId: String, val id: String)

/** One run owns the endpoint; closing it revokes that exact lease and cancels pending calls. */
interface ComputerEndpoint : AutoCloseable {
    val descriptor: ComputerEndpointDescriptor
}

/** Ephemeral transport values only. Tokens and source text never enter journal records. */
data class ComputerEndpointDescriptor(val url: String, val token: String, val piExtension: String)

/** Explicit disabled config replaces old persisted native endpoints on both start and resume. */
fun computerCodexConfig(base: JsonObject, endpoint: ComputerEndpointDescriptor?): JsonObject = buildJsonObject {
    base.forEach { (key, value) -> put(key, value) }
    put("mcp_servers.magicpaper_computer", buildJsonObject {
        put("url", endpoint?.url ?: "http://127.0.0.1:1/mcp")
        put("enabled", endpoint != null)
        put("required", endpoint != null)
        put("default_tools_approval_mode", "approve")
        put("startup_timeout_sec", 10)
        put("tool_timeout_sec", 30)
        put("http_headers", buildJsonObject { if (endpoint != null) put("Authorization", "Bearer ${endpoint.token}") })
    })
}
