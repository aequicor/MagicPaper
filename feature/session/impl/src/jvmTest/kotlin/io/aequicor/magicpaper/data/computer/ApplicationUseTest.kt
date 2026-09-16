package io.aequicor.magicpaper.data.computer

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

internal class FakeApplicationDesktop : ApplicationDesktop {
    val calls = mutableListOf<JsonObject>()
    var closed = false
    var failInput = false
    var before: (() -> Unit) = {}
    override fun request(args: JsonObject, checkActive: () -> Unit): JsonObject {
        before(); checkActive(); check(!closed)
        calls += args
        return when (args.requiredString("action")) {
            "windows" -> buildJsonObject { put("windows", buildJsonArray { add(buildJsonObject { put("window_id", "w") }) }) }
            "inspect" -> buildJsonObject {
                put("window_id", args.requiredString("window_id"))
                putJsonArray("elements") {
                    add(buildJsonObject {
                        put("element_id", "button")
                        putJsonArray("actions") { add("invoke"); add("set_value") }
                    })
                }
            }
            "screenshot" -> buildJsonObject {
                val fixture = FakeComputerDesktop()
                put("window_id", "w")
                put("png", java.util.Base64.getEncoder().encodeToString(fixture.capture(fixture.displays().first()).png))
            }
            else -> { check(!failInput) { "Provider timeout" }; buildJsonObject { put("performed", true) } }
        }
    }
    override fun close() { closed = true }
}
internal fun JsonObject.snapshotId() = Json.parseToJsonElement(get("content")!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    .jsonObject.requiredString("snapshot_id")

class ApplicationUseTest {
    @Test fun settingsDoNotAcquireAndScopesAreIndependent() = runBlocking {
        val desktop = FakeComputerDesktop()
        val native = FakeApplicationDesktop()
        val computer = DesktopComputerUse(desktop, applicationFactory = { native })
        assertNull(computer.begin("s"))
        computer.configure(ComputerAccess.OFF, ComputerAccess.SCREEN)
        assertNull(computer.grant("s"))
        assertTrue(native.calls.isEmpty())
        val epoch = computer.begin("s")!!
        assertTrue(computer.execute("s", epoch, request("screenshot")).failed())
        assertEquals(0, desktop.captures)
        assertFalse(computer.executeApplication("s", epoch, request("windows")).failed())
        val shot = computer.executeApplication("s", epoch, request("inspect") { put("window_id", "w") })
        assertTrue(computer.executeApplication("s", epoch, input(shot.snapshotId())).failed())
        assertEquals(listOf("windows", "inspect"), native.calls.map { it.requiredString("action") })
        computer.disable()
    }

    @Test fun applyingOffPolicyAlsoRevokesLegacyManualGrant() = runBlocking {
        val computer = DesktopComputerUse(FakeComputerDesktop())
        computer.enable("s", ComputerAccess.CONTROL)
        val epoch = computer.grant("s")!!
        computer.configure(ComputerAccess.OFF, ComputerAccess.OFF)
        assertNull(computer.grant("s"))
        assertTrue(computer.execute("s", epoch, request("screenshot")).failed())
        assertNull(computer.begin("s"))
    }

    private fun input(snapshot: String, window: String = "w", element: String = "button", action: String = "invoke") = request(action) {
        put("window_id", window); put("snapshot_id", snapshot); put("element_id", element)
        if (action == "set_value") put("text", "Привет ✨")
    }

    @Test fun staleWrongWindowUnsupportedAndDuplicateInputNeverReachNative() = runBlocking {
        var now = 1L
        val native = FakeApplicationDesktop()
        val desktop = FakeComputerDesktop()
        val computer = DesktopComputerUse(desktop, applicationFactory = { native }, clock = { now })
        computer.configure(ComputerAccess.OFF, ComputerAccess.CONTROL)
        val epoch = computer.begin("s")!!
        val inspected = computer.executeApplication("s", epoch, request("inspect") { put("window_id", "w") })
        val id = inspected.snapshotId()
        for (args in listOf(input(id, window = "other"), input(id, element = "other"), input(id, action = "click"),
            input("old"), JsonObject(input(id) + ("x" to JsonPrimitive(1))))) {
            assertTrue(computer.executeApplication("s", epoch, args).failed())
        }
        now = 30_000_000_002
        assertTrue(computer.executeApplication("s", epoch, input(id)).failed())
        now = 2
        val results = List(2) { async { computer.executeApplication("s", epoch, input(id, action = "set_value")) } }.awaitAll()
        assertEquals(1, results.count { !it.failed() })
        assertEquals(1, native.calls.count { it.requiredString("action") == "set_value" })
        assertEquals("Привет ✨", native.calls.first { it.requiredString("action") == "set_value" }.requiredString("text"))
        assertTrue(desktop.performed.isEmpty())
        computer.disable()
    }

    @Test fun unknownInputOutcomeConsumesAuthorityAndScreenshotCannotAuthorizeInput() = runBlocking {
        val native = FakeApplicationDesktop()
        val computer = DesktopComputerUse(FakeComputerDesktop(), applicationFactory = { native })
        computer.configure(ComputerAccess.OFF, ComputerAccess.CONTROL)
        val epoch = computer.begin("s")!!
        val id = computer.executeApplication("s", epoch, request("inspect") { put("window_id", "w") }).snapshotId()
        native.failInput = true
        assertTrue(computer.executeApplication("s", epoch, input(id)).failed())
        assertTrue(computer.executeApplication("s", epoch, input(id)).failed())
        assertEquals(1, native.calls.count { it.requiredString("action") == "invoke" })
        val fresh = computer.executeApplication("s", epoch, request("inspect") { put("window_id", "w") }).snapshotId()
        val shot = computer.executeApplication("s", epoch, request("screenshot") { put("window_id", "w") })
        assertFalse(shot.failed())
        assertEquals("image", shot["content"]!!.jsonArray[1].jsonObject.requiredString("type"))
        assertTrue(computer.executeApplication("s", epoch, input(fresh)).failed())
        computer.disable()
    }

    @Test fun policyChangeRevokesInFlightAndOldReleaseCannotRevokeNextTurn() = runBlocking {
        val hosts = mutableListOf<FakeApplicationDesktop>()
        val computer = DesktopComputerUse(FakeComputerDesktop(), applicationFactory = { FakeApplicationDesktop().also(hosts::add) })
        computer.configure(ComputerAccess.CONTROL, ComputerAccess.CONTROL)
        val epoch = computer.begin("s")!!
        assertNull(computer.begin("other"))
        computer.executeApplication("s", epoch, request("inspect") { put("window_id", "w") })
        hosts.single().before = { computer.configure(ComputerAccess.OFF, ComputerAccess.SCREEN) }
        assertTrue(computer.executeApplication("s", epoch, request("screenshot") { put("window_id", "w") }).failed())
        assertTrue(hosts.single().closed)
        assertNull(computer.grant("s"))
        val next = computer.begin("s")!!
        computer.release("s", epoch)
        assertEquals(next, computer.grant("s"))
        assertTrue(computer.executeApplication("s", epoch, request("windows")).failed())
        assertFalse(computer.executeApplication("s", next, request("windows")).failed())
        computer.release("s", next)
        assertTrue(hosts.all { it.closed })
        assertNull(computer.grant("s"))
    }

    @Test fun policyAndInvocationIdentityHaveDifferentPersistence() {
        val settings = AppSettings(computerAccess = ComputerAccess.SCREEN, applicationAccess = ComputerAccess.CONTROL)
        assertEquals(settings, Json.decodeFromString<AppSettings>(Json.encodeToString(settings)))
        assertEquals(ComputerAccess.OFF, Json.decodeFromString<AppSettings>("{}").applicationAccess)
        val session = CodingSession("s", "p", "s", 1, acquireComputerAccess = true)
        val encoded = Json.encodeToString(session)
        assertFalse(encoded.contains("acquireComputerAccess"))
        assertFalse(Json.decodeFromString<CodingSession>(encoded).acquireComputerAccess)
        val chat = ChatSession("s", "chat", 1, 1, acquireComputerAccess = true)
        val chatJson = Json.encodeToString(chat)
        assertFalse(chatJson.contains("acquireComputerAccess"))
        assertFalse(Json.decodeFromString<ChatSession>(chatJson).acquireComputerAccess)
    }
}
