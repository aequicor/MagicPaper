package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.*
import java.io.StringWriter
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/** Tests transport ownership and cleanup without calling a model or executing a command. */
class CodexApprovalRoutingTest {
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun accumulator(): Any = Class.forName("io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription\$CodingAccumulator")
        .getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    private class Fixture {
        val home = Files.createTempDirectory("codex-approvals-")
        val service = CodexAppServerOpenAiSubscription(Json, home)
        val output = StringWriter()
        val params = buildJsonObject { put("threadId", "thread"); put("turnId", "turn"); put("itemId", "item"); put("command", "echo approved") }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fixture(): Fixture = Fixture().also { f ->
        field(f.service, "writer").set(f.service, f.output.buffered())
        val run = accumulator()
        field(run, "turnId").set(run, "turn")
        (field(f.service, "codingRuns").get(f.service) as MutableMap<String, Any>)["thread"] = run
        (field(f.service, "codingContexts").get(f.service) as MutableMap<String, CodingSession>)["thread"] = CodingSession("session", "project", "Session", 0)
        (field(f.service, "codingSessions").get(f.service) as MutableMap<String, String>)["session"] = "thread"
    }
    private fun notify(f: Fixture, method: String, params: JsonObject) {
        f.service.javaClass.getDeclaredMethod("handleNotification", String::class.java, JsonObject::class.java).apply { isAccessible = true }.invoke(f.service, method, params)
    }
    private fun Fixture.close() { service.close(); home.toFile().deleteRecursively() }

    @Test fun scopesRequestToActiveCodingTurnAndPreservesStringRpcId() = runBlocking {
        val f = fixture()
        try {
            for (bad in listOf(f.params + ("threadId" to JsonPrimitive("chat")), f.params + ("turnId" to JsonPrimitive("old-turn")))) {
                assertFalse(f.service.handleServerRequest(JsonPrimitive(1), "item/commandExecution/requestApproval", JsonObject(bad)))
            }
            assertTrue(f.service.handleServerRequest(JsonPrimitive("approval:1"), "item/commandExecution/requestApproval", f.params))
            assertEquals("", f.output.toString())
            f.service.respondCodingApproval(f.service.codingApprovals.value.single().id, CodingApprovalDecision.ALLOW_ONCE)
            val response = Json.parseToJsonElement(f.output.toString().trim()).jsonObject
            assertEquals(JsonPrimitive("approval:1"), response["id"])
            assertEquals(JsonPrimitive("accept"), response["result"]!!.jsonObject["decision"])
        } finally { f.close() }
    }

    @Test fun engineResolutionAndTurnCompletionInvalidateButtons() = runBlocking {
        val f = fixture()
        try {
            f.service.handleServerRequest(JsonPrimitive(1), "item/commandExecution/requestApproval", f.params)
            val stale = f.service.codingApprovals.value.single().id
            notify(f, "serverRequest/resolved", buildJsonObject { put("threadId", "thread"); put("requestId", 1) })
            f.service.respondCodingApproval(stale, CodingApprovalDecision.ALLOW_ONCE)
            assertEquals("", f.output.toString())
            f.service.handleServerRequest(JsonPrimitive(2), "item/commandExecution/requestApproval", f.params)
            notify(f, "turn/completed", buildJsonObject { put("threadId", "thread"); putJsonObject("turn") { put("id", "turn") } })
            assertTrue(f.service.codingApprovals.value.isEmpty())
            assertFalse(f.service.handleServerRequest(JsonPrimitive(3), "item/commandExecution/requestApproval", f.params))
        } finally { f.close() }
    }

    @Test fun approvalCannotBeWrittenIntoAReplacementConnection(): Unit = runBlocking {
        val f = fixture()
        try {
            f.service.handleServerRequest(JsonPrimitive(1), "item/commandExecution/requestApproval", f.params)
            val other = StringWriter()
            field(f.service, "writer").set(f.service, other.buffered())
            f.service.respondCodingApproval(f.service.codingApprovals.value.single().id, CodingApprovalDecision.ALLOW_ONCE)
            assertEquals("", other.toString())
            assertEquals("", f.output.toString())
            assertNotNull(f.service.codingApprovals.value.single().error)
        } finally { f.close() }
    }

    @Test fun connectionLossRemovesAllApprovals() {
        val f = fixture()
        try {
            f.service.handleServerRequest(JsonPrimitive(1), "item/commandExecution/requestApproval", f.params)
            f.service.javaClass.getDeclaredMethod("failAll", String::class.java).apply { isAccessible = true }.invoke(f.service, "connection lost")
            assertTrue(f.service.codingApprovals.value.isEmpty())
        } finally { f.close() }
    }
}
