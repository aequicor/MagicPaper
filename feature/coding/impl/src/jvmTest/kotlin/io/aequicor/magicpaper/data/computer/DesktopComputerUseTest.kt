package io.aequicor.magicpaper.data.computer

import io.aequicor.magicpaper.domain.ComputerAccess
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

internal class FakeComputerDesktop : ComputerDesktop {
    override val supported = true
    var monitors = listOf(ComputerDisplay("retina", 2560, 1440, -2560, 0))
    val performed = mutableListOf<ComputerAction>()
    var captures = 0
    var denied = false
    var onCapture: () -> Unit = {}
    var onPerform: ((() -> Unit) -> Unit)? = null
    override fun checkPermissions(access: ComputerAccess, request: Boolean) { check(!denied) { "Screen recording permission denied" } }
    override fun displays() = monitors
    override fun capture(display: ComputerDisplay): DesktopCapture {
        captures++
        onCapture()
        val image = BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream().use { ImageIO.write(image, "png", it); it.toByteArray() }
        return DesktopCapture(bytes, 1600, 900)
    }
    override fun perform(action: ComputerAction, display: ComputerDisplay, checkActive: () -> Unit) {
        checkActive()
        onPerform?.invoke(checkActive)
        checkActive()
        performed += action
    }
}

internal fun request(action: String, block: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("action", action); block() }
internal fun JsonObject.screenshotId() = Json.parseToJsonElement(get("content")!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    .jsonObject["screenshot_id"]!!.jsonPrimitive.content
internal fun JsonObject.failed() = get("isError") == JsonPrimitive(true)

class DesktopComputerUseTest {
    @Test fun noAccessUntilUserEnablesIt(): Unit = runBlocking {
        val desktop = FakeComputerDesktop()
        val computer = DesktopComputerUse(desktop)
        assertNull(computer.grant("a"))
        assertTrue(computer.execute("a", 0, request("screenshot")).failed())
        assertEquals(0, desktop.captures)
    }

    @Test fun screenshotReturnsImageAndPreviewButScreenModeCannotSendInput(): Unit = runBlocking {
        val desktop = FakeComputerDesktop()
        val computer = DesktopComputerUse(desktop)
        computer.enable("a", ComputerAccess.SCREEN)
        val epoch = computer.grant("a")!!
        val screenshot = computer.execute("a", epoch, request("screenshot"))
        assertFalse(screenshot.failed())
        val image = screenshot["content"]!!.jsonArray[1].jsonObject
        assertEquals("image", image["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", image["mimeType"]!!.jsonPrimitive.content)
        assertEquals(computer.state.value.preview!!.dataBase64, image["data"]!!.jsonPrimitive.content)
        for (action in listOf("click", "double_click", "move", "drag", "scroll", "type", "key")) {
            assertTrue(computer.execute("a", epoch, request(action) { put("screenshot_id", screenshot.screenshotId()) }).failed())
        }
        assertTrue(desktop.performed.isEmpty())
        assertFalse(computer.state.value.busy)
    }

    @Test fun controlUsesImageCoordinatesAndConsumesPreviousScreenshot(): Unit = runBlocking {
        val desktop = FakeComputerDesktop()
        val computer = DesktopComputerUse(desktop)
        computer.enable("a", ComputerAccess.CONTROL)
        val epoch = computer.grant("a")!!
        val before = computer.execute("a", epoch, request("screenshot"))
        val click = request("click") { put("screenshot_id", before.screenshotId()); put("x", 800); put("y", 450) }
        val after = computer.execute("a", epoch, click)
        assertFalse(after.failed())
        assertEquals(1280, desktop.performed.single().x)
        assertEquals(720, desktop.performed.single().y)
        assertNotEquals(before.screenshotId(), after.screenshotId())
        assertTrue(computer.execute("a", epoch, click).failed())
        assertEquals(1, desktop.performed.size)
    }

    @Test fun invalidArgumentsAreRejectedBeforeAnyInput(): Unit = runBlocking {
        val desktop = FakeComputerDesktop()
        val computer = DesktopComputerUse(desktop)
        computer.enable("a", ComputerAccess.CONTROL)
        val epoch = computer.grant("a")!!
        val id = computer.execute("a", epoch, request("screenshot")).screenshotId()
        val invalid = listOf(
            request("click") { put("x", -1); put("y", 0) },
            request("click") { put("x", 1600); put("y", 0) },
            request("click") { put("x", "2"); put("y", 0) },
            request("click") { put("x", 1.5); put("y", 0) },
            request("click") { put("x", 2); put("y", 900) },
            request("drag") { put("x", 0); put("y", 0); put("to_x", 1600); put("to_y", 1) },
            request("key") { put("keys", buildJsonArray { add("CTRL"); add("NONSENSE") }) },
            request("key") { put("keys", buildJsonArray { add("CTRL"); add("CTRL") }) },
            request("scroll") { put("x", 2); put("y", 2); put("amount", 21) },
            request("type") { put("text", "x".repeat(10001)) },
            request("type") { put("text", "") },
            request("type") { put("text", "hello"); put("execute", "command") },
        )
        invalid.forEach { args ->
            assertTrue(computer.execute("a", epoch, JsonObject(args + ("screenshot_id" to JsonPrimitive(id)))).failed(), args.toString())
        }
        assertTrue(desktop.performed.isEmpty())
    }

    @Test fun oldScreenshotAndDisplayChangesRequireNewCapture(): Unit = runBlocking {
        var now = 1L
        val desktop = FakeComputerDesktop()
        val computer = DesktopComputerUse(desktop) { now }
        computer.enable("a", ComputerAccess.CONTROL)
        val epoch = computer.grant("a")!!
        val id = computer.execute("a", epoch, request("screenshot")).screenshotId()
        val click = request("click") { put("screenshot_id", id); put("x", 1); put("y", 1) }
        now += 30_000_000_001L
        assertTrue(computer.execute("a", epoch, click).failed())
        now = 2L
        desktop.monitors = desktop.monitors.map { it.copy(x = 0) }
        assertTrue(computer.execute("a", epoch, click).failed())
        assertTrue(desktop.performed.isEmpty())
    }

    @Test fun displaySelectionAndUnicodeInput(): Unit = runBlocking {
        val desktop = FakeComputerDesktop().apply { monitors += ComputerDisplay("external", 1920, 1080) }
        val computer = DesktopComputerUse(desktop)
        computer.enable("a", ComputerAccess.CONTROL)
        val epoch = computer.grant("a")!!
        val list = computer.execute("a", epoch, request("displays"))
        assertContains(list.toString(), "external")
        assertTrue(computer.execute("a", epoch, request("screenshot") { put("display_id", "missing") }).failed())
        val shot = computer.execute("a", epoch, request("screenshot") { put("display_id", "external") })
        assertFalse(shot.failed())
        assertFalse(computer.execute("a", epoch, request("type") { put("screenshot_id", shot.screenshotId()); put("text", "Привет, мир ✨") }).failed())
        assertEquals("Привет, мир ✨", desktop.performed.single().text)
    }

    @Test fun permissionsAndSessionOwnershipAreNotInherited(): Unit = runBlocking {
        val desktop = FakeComputerDesktop()
        val computer = DesktopComputerUse(desktop)
        computer.enable("a", ComputerAccess.SCREEN)
        val epoch = computer.grant("a")!!
        computer.enable("worker", ComputerAccess.CONTROL)
        assertNull(computer.grant("worker"))
        assertTrue(computer.execute("worker", epoch, request("screenshot")).failed())
        computer.disable("worker")
        assertNotNull(computer.grant("a"))
        computer.disable("a")
        computer.enable("a", ComputerAccess.CONTROL)
        assertTrue(computer.execute("a", epoch, request("screenshot")).failed())
        computer.release("a", epoch)
        assertNotNull(computer.grant("a"))
    }

    @Test fun osDenialIsVisibleAndDoesNotGrantAccess(): Unit = runBlocking {
        val desktop = FakeComputerDesktop().apply { denied = true }
        val computer = DesktopComputerUse(desktop)
        computer.enable("a", ComputerAccess.CONTROL)
        assertEquals(ComputerAccess.OFF, computer.state.value.access)
        assertContains(computer.state.value.detail, "permission denied")
        assertNull(computer.grant("a"))
    }

    @Test fun disconnectDuringCaptureDiscardsImage(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val desktop = FakeComputerDesktop().apply { onCapture = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        val computer = DesktopComputerUse(desktop)
        computer.enable("a", ComputerAccess.SCREEN)
        val epoch = computer.grant("a")!!
        val call = async(Dispatchers.Default) { computer.execute("a", epoch, request("screenshot")) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // busy operations still have a valid grant; cancellation/stop can reach them.
            assertEquals(epoch, computer.grant("a"))
            computer.disable("a")
        } finally { release.countDown() }
        assertTrue(call.await().failed())
        assertNull(computer.state.value.preview)
        assertEquals(ComputerAccess.OFF, computer.state.value.access)
    }

    @Test fun disconnectStopsInputBetweenSteps(): Unit = runBlocking {
        val desktop = FakeComputerDesktop()
        val computer = DesktopComputerUse(desktop)
        computer.enable("a", ComputerAccess.CONTROL)
        val epoch = computer.grant("a")!!
        val id = computer.execute("a", epoch, request("screenshot")).screenshotId()
        desktop.onPerform = { check -> computer.disable("a"); check() }
        assertTrue(computer.execute("a", epoch, request("drag") {
            put("screenshot_id", id); put("x", 0); put("y", 0); put("to_x", 100); put("to_y", 100)
        }).failed())
        assertTrue(desktop.performed.isEmpty())
        assertNull(computer.state.value.preview)
    }

    @Test fun concurrentInputsBasedOnSameImageCannotBothExecute(): Unit = runBlocking {
        val desktop = FakeComputerDesktop()
        val computer = DesktopComputerUse(desktop)
        computer.enable("a", ComputerAccess.CONTROL)
        val epoch = computer.grant("a")!!
        val id = computer.execute("a", epoch, request("screenshot")).screenshotId()
        val click = request("click") { put("screenshot_id", id); put("x", 0); put("y", 0) }
        val results = List(2) { async { computer.execute("a", epoch, click) } }.awaitAll()
        assertEquals(1, results.count { !it.failed() })
        assertEquals(1, desktop.performed.size)
    }

    @Test fun protocolCancellationStopsInputBetweenSteps(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val desktop = FakeComputerDesktop()
        val computer = DesktopComputerUse(desktop)
        computer.enable("a", ComputerAccess.CONTROL)
        val epoch = computer.grant("a")!!
        val id = computer.execute("a", epoch, request("screenshot")).screenshotId()
        desktop.onPerform = { checkActive -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); checkActive() }
        val call = async(Dispatchers.Default) { computer.execute("a", epoch, request("drag") {
            put("screenshot_id", id); put("x", 0); put("y", 0); put("to_x", 100); put("to_y", 100)
        }) }
        try { assertTrue(entered.await(5, TimeUnit.SECONDS)); call.cancel() }
        finally { release.countDown() }
        call.join()
        assertTrue(desktop.performed.isEmpty())
        assertFalse(computer.state.value.busy)
    }
}
