package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.tools.ToolPhase
import kotlinx.serialization.json.*
import kotlin.test.*

class CodexNativeToolResultsTest {
    private fun command(status: String = "inProgress", exit: Int? = null) = buildJsonObject {
        put("id", "check"); put("type", "commandExecution"); put("status", status)
        exit?.let { put("exitCode", it) }; put("aggregatedOutput", "partial output")
    }
    private fun response(vararg items: JsonObject) = buildJsonObject { putJsonObject("thread") {
        put("id", "thread"); putJsonArray("turns") { add(buildJsonObject {
            put("items", JsonArray(items.toList()))
        }) }
    } }

    @Test fun reconciliationRequiresExactIdentityAndConfirmedExit() {
        for (item in listOf(command(), command("completed"))) {
            assertTrue(CodexNativeToolResults.read(response(item), "thread", setOf("check")).isEmpty())
        }
        assertTrue(CodexNativeToolResults.read(response(command("completed", 0)), "other", setOf("check")).isEmpty())
        assertTrue(CodexNativeToolResults.read(response(command("completed", 0)), "thread", setOf("other")).isEmpty())
        assertEquals(ToolPhase.FAILED, CodexNativeToolResults.read(response(command("completed", 1)), "thread", setOf("check")).single().phase)
        assertEquals(ToolPhase.SUCCEEDED, CodexNativeToolResults.read(response(command("completed", 0)), "thread", setOf("check")).single().phase)
    }

    @Test fun conflictingTerminalEvidenceAndModelProseCannotProveSuccess() {
        val conflicts = response(command("completed", 0), command("completed", 1))
        assertTrue(CodexNativeToolResults.read(conflicts, "thread", setOf("check")).isEmpty())
        val prose = buildJsonObject {
            put("id", "check"); put("type", "agentMessage"); put("status", "completed"); put("text", "BUILD SUCCESSFUL")
        }
        assertTrue(CodexNativeToolResults.read(response(prose), "thread", setOf("check")).isEmpty())
    }

    @Test fun readOnlyPolicyNeverInheritsWriteRootsOrEscalation() {
        val policy = CodexNativeAdapter().readOnlyAccessPolicy()
        assertEquals(JsonPrimitive("never"), policy.approvalConfiguration["approvalPolicy"])
        assertEquals(JsonPrimitive("readOnly"), policy.sandboxPolicy["type"])
        assertFalse("writableRoots" in policy.sandboxPolicy)
        assertEquals(JsonObject(emptyMap()), policy.threadConfiguration["mcp_servers"])
    }
}
