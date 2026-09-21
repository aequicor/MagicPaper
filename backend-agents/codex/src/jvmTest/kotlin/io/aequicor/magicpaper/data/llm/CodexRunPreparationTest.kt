package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.backend.CodexRunRequest
import io.aequicor.magicpaper.domain.CodingInteractionMode
import kotlinx.serialization.json.*
import kotlin.test.*

class CodexRunPreparationTest {
    private fun request(mode: CodingInteractionMode) = CodexRunRequest(
        workingDirectory = System.getProperty("java.io.tmpdir"), modelId = "selected-model", modelProvider = "provider",
        providerConfiguration = buildJsonObject {
            put("sandbox_mode", "danger-full-access"); put("approval_policy", "untrusted")
            putJsonObject("mcp_servers.foreign") { put("url", "http://foreign.invalid") }
        },
        toolConfiguration = buildJsonObject {
            putJsonObject("mcp_servers.magicpaper_tools") { put("url", "http://127.0.0.1:1234/mcp") }
        },
        instructions = "Resolved application instructions", input = buildJsonArray { add("prepared input") },
        mode = mode, effort = "high", nativeSessionId = "saved-thread", serviceName = "MagicPaper",
        baseInstructions = "Read only",
    )

    @Test fun restrictedPreparationKeepsAppToolsAndRejectsInheritedWriteAuthorityOnStartAndResume() {
        for (mode in listOf(CodingInteractionMode.PLANNING, CodingInteractionMode.RESEARCH)) {
            val request = request(mode)
            val payloads = CodexNativeAdapter().prepareRun(request)
            for (thread in listOf(payloads.threadStart, checkNotNull(payloads.threadResume))) {
                assertEquals(JsonPrimitive("read-only"), thread["sandbox"])
                assertEquals(JsonPrimitive("never"), thread["approvalPolicy"])
                val config = thread.getValue("config").jsonObject
                assertEquals(JsonPrimitive("read-only"), config["sandbox_mode"])
                assertEquals(JsonPrimitive("never"), config["approval_policy"])
                assertEquals(setOf("magicpaper_tools"), config.getValue("mcp_servers").jsonObject.keys)
                assertFalse(config.keys.any { it.startsWith("mcp_servers.") })
            }
            assertEquals(JsonPrimitive("readOnly"), payloads.turnStart.getValue("sandboxPolicy").jsonObject["type"])
            assertEquals(request.input, payloads.turnStart["input"])
            assertEquals(JsonPrimitive("saved-thread"), payloads.threadResume?.get("threadId"))
        }
    }

    @Test fun preparedToolConfigurationCannotAlterNativeAccessPolicy() {
        assertFailsWith<IllegalArgumentException> {
            CodexNativeAdapter().prepareRun(request(CodingInteractionMode.RESEARCH).copy(
                toolConfiguration = buildJsonObject { put("sandbox_mode", "danger-full-access") }))
        }
    }

    @Test fun ordinaryPreparationPreservesExistingProviderEndpoints() {
        val payload = CodexNativeAdapter().prepareRun(request(CodingInteractionMode.CODE)).threadStart
        val config = payload.getValue("config").jsonObject
        assertContains(config, "mcp_servers.foreign")
        assertContains(config, "mcp_servers.magicpaper_tools")
    }
}
