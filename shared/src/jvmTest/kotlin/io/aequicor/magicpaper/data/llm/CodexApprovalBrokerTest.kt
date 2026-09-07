package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CodexApprovalBrokerTest {
    private val session = CodingSession("session", "project", "Исполнитель", 0)
    private fun params(thread: String = "thread", turn: String = "turn", extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("threadId", thread); put("turnId", turn); put("itemId", "item")
        put("command", "rm -rf ./generated"); put("cwd", "/project"); put("reason", "Удалить созданные файлы?")
        extra()
    }

    @Test fun waitsForExplicitDecisionAndRepliesExactlyOnceWithOriginalIdType() = runTest {
        val replies = mutableListOf<JsonObject>()
        val broker = CodexApprovalBroker { _, _ -> }
        for (wireId in listOf(JsonPrimitive(7), JsonPrimitive("7"))) {
            assertTrue(broker.receive(wireId, "item/commandExecution/requestApproval", params(), session, null) { replies += it })
        }
        assertEquals(2, broker.requests.value.size)
        assertTrue(replies.isEmpty())
        val first = broker.requests.value.first()
        assertContains(first.details, "rm -rf ./generated")
        broker.respond(first.id, CodingApprovalDecision.ALLOW_ONCE)
        broker.respond(first.id, CodingApprovalDecision.ALLOW_ONCE)
        broker.respond(broker.requests.value.single().id, CodingApprovalDecision.DENY)
        assertEquals(listOf(JsonPrimitive(7), JsonPrimitive("7")), replies.map { it["id"] })
        assertEquals(listOf("accept", "decline"), replies.map { it["result"]!!.jsonObject["decision"]!!.jsonPrimitive.content })
        assertTrue(broker.requests.value.isEmpty())
    }

    @Test fun duplicateRequestCannotReplaceTheReviewedOperation() = runTest {
        val broker = CodexApprovalBroker { _, _ -> }
        broker.receive(JsonPrimitive(1), "item/commandExecution/requestApproval", params(), session, null) {}
        val shown = broker.requests.value.single()
        broker.receive(JsonPrimitive(1), "item/commandExecution/requestApproval", params { put("command", "different operation") }, session, null) {}
        assertEquals(shown, broker.requests.value.single())
    }

    @Test fun concurrentClickDoesNotReplyTwiceOrBlockOtherSessions() = runTest {
        val sent = CompletableDeferred<Unit>()
        val broker = CodexApprovalBroker { _, _ -> }
        var count = 0
        broker.receive(JsonPrimitive(1), "item/commandExecution/requestApproval", params(), session, null) { count++; sent.await() }
        val id = broker.requests.value.single().id
        val sending = launch { broker.respond(id, CodingApprovalDecision.ALLOW_ONCE) }
        runCurrent()
        assertTrue(broker.requests.value.single().submitting)
        broker.respond(id, CodingApprovalDecision.DENY)
        broker.receive(JsonPrimitive(2), "item/commandExecution/requestApproval", params("other"), session.copy(id = "other"), null) {}
        assertEquals(2, broker.requests.value.size)
        assertEquals(1, count)
        sent.complete(Unit); sending.join()
        assertEquals("other", broker.requests.value.single().sessionId)
    }

    @Test fun resolvedCompletedCancelledAndDisconnectedRequestsCannotBeAnswered() = runTest {
        val broker = CodexApprovalBroker { _, _ -> }
        var replies = 0
        fun add(id: Int, thread: String = "thread") {
            broker.receive(JsonPrimitive(id), "item/commandExecution/requestApproval", params(thread), session, null) { replies++ }
        }
        add(1); add(2, "other")
        val removed = broker.requests.value.first().id
        broker.resolved("wrong-thread", JsonPrimitive(1))
        assertEquals(2, broker.requests.value.size)
        broker.resolved("thread", JsonPrimitive(1))
        broker.respond(removed, CodingApprovalDecision.ALLOW_ONCE)
        broker.clearTurn("other", "wrong-turn")
        assertEquals(1, broker.requests.value.size)
        broker.clearTurn("other", "turn")
        add(3); broker.completeItem("thread", "item")
        assertTrue(broker.requests.value.isEmpty())
        add(4); val stale = broker.requests.value.single().id; broker.clear()
        broker.respond(stale, CodingApprovalDecision.ALLOW_ONCE)
        assertEquals(0, replies)
    }

    @Test fun permissionGrantIsExactlyRequestedAndLimitedToTurn() = runTest {
        val broker = CodexApprovalBroker { _, _ -> }
        val replies = mutableListOf<JsonObject>()
        val permissions = buildJsonObject { putJsonObject("network") { put("enabled", true) }; putJsonObject("fileSystem") { putJsonArray("write") { add("/tmp/output") } } }
        for (decision in CodingApprovalDecision.entries) {
            broker.receive(JsonPrimitive(decision.ordinal), "item/permissions/requestApproval", params { put("permissions", permissions) }, session, null) { replies += it }
            broker.respond(broker.requests.value.single().id, decision)
        }
        assertEquals(permissions, replies[0]["result"]!!.jsonObject["permissions"])
        assertEquals(buildJsonObject {}, replies[1]["result"]!!.jsonObject["permissions"])
        replies.forEach { assertEquals(JsonPrimitive("turn"), it["result"]!!.jsonObject["scope"]) }
    }

    @Test fun networkAndFileChangesShowActualTargetAndDiff() = runTest {
        val broker = CodexApprovalBroker { _, _ -> }
        broker.receive(JsonPrimitive(1), "item/commandExecution/requestApproval", params {
            putJsonObject("networkApprovalContext") { put("host", "example.com:8443"); put("protocol", "https") }
        }, session, null) {}
        assertEquals(CodingApprovalKind.NETWORK, broker.requests.value.single().kind)
        assertContains(broker.requests.value.single().details, "example.com:8443")
        broker.clear()
        val item = buildJsonObject { putJsonArray("changes") { add(buildJsonObject {
            put("path", "/tmp/a.txt"); put("diff", "-old\n+new"); putJsonObject("kind") { put("type", "update") }
        }) } }
        broker.receive(JsonPrimitive(2), "item/fileChange/requestApproval", params(), session, item) {}
        assertTrue(broker.requests.value.single().canAllow)
        assertContains(broker.requests.value.single().details, "/tmp/a.txt\nИзменение файла\n-old\n+new")
    }

    @Test fun missingPreviewAndRestrictedDecisionsCannotBeApproved() = runTest {
        val broker = CodexApprovalBroker { _, _ -> }
        var response: JsonObject? = null
        broker.receive(JsonPrimitive(1), "item/commandExecution/requestApproval", params {
            putJsonArray("availableDecisions") { add("cancel") }
        }, session, null) { response = it }
        val request = broker.requests.value.single()
        assertFalse(request.canAllow)
        broker.respond(request.id, CodingApprovalDecision.ALLOW_ONCE)
        assertNull(response)
        broker.respond(request.id, CodingApprovalDecision.DENY)
        assertEquals(JsonPrimitive("cancel"), response!!["result"]!!.jsonObject["decision"])
        broker.receive(JsonPrimitive(2), "item/fileChange/requestApproval", params(), session, null) {}
        assertFalse(broker.requests.value.single().canAllow)
        broker.clear()
        broker.receive(JsonPrimitive(3), "item/fileChange/requestApproval", params(), session,
            buildJsonObject { putJsonArray("changes") { add(buildJsonObject { put("path", "/tmp/a") }) } }) {}
        assertFalse(broker.requests.value.single().canAllow)
        assertFalse(broker.receive(JsonPrimitive(3), "item/tool/call", params(), session, null) {})
    }

    @Test fun uncertainWriteCannotBeRetriedWithAnotherDecision() = runTest {
        val broker = CodexApprovalBroker { _, _ -> }
        var writes = 0
        broker.receive(JsonPrimitive(1), "item/commandExecution/requestApproval", params(), session, null) { writes++; error("broken pipe") }
        val id = broker.requests.value.single().id
        broker.respond(id, CodingApprovalDecision.ALLOW_ONCE)
        assertNotNull(broker.requests.value.single().error)
        broker.respond(id, CodingApprovalDecision.DENY)
        assertEquals(1, writes)
    }
}
