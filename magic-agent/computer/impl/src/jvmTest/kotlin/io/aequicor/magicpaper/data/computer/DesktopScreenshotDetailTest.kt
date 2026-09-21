package io.aequicor.magicpaper.data.computer

import io.aequicor.magicpaper.domain.ComputerAccess
import java.awt.image.BufferedImage
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopScreenshotDetailTest {
    private fun crop(id: String, x: Int, y: Int, width: Int, height: Int) = request("screenshot") {
        put("screenshot_id", id)
        put("region", buildJsonObject { put("x", x); put("y", y); put("width", width); put("height", height) })
    }
    private fun JsonObject.metadata() = Json.parseToJsonElement(get("content")!!.jsonArray[0].jsonObject.requiredString("text")).jsonObject
    private fun JsonObject.image() = get("content")!!.jsonArray.single { it.jsonObject["type"] == JsonPrimitive("image") }.jsonObject

    @Test fun nativeCaptureRetainsDetailAndDefaultsToPngWithoutInput(): Unit = runBlocking {
        val desktop = FakeComputerDesktop().apply { monitors = listOf(ComputerDisplay("retina", 1920, 1080)); nativeScale = 2 }
        val computer = testComputer(desktop)
        computer.enableForTest("a", ComputerAccess.SCREEN)
        val shot = computer.execute("a", computer.grant("a")!!, request("screenshot") { put("resolution", "native") })
        assertFalse(shot.failed())
        assertEquals(JsonPrimitive(3840), shot.metadata()["width"])
        assertEquals(JsonPrimitive(2160), shot.metadata()["height"])
        assertEquals("image/png", shot.image().requiredString("mimeType"))
        val decoded = ImageIO.read(Base64.getDecoder().decode(shot.image().requiredString("data")).inputStream())
        try { assertEquals(3840, decoded.width); assertEquals(2160, decoded.height) } finally { decoded.flush() }
        assertTrue(desktop.performed.isEmpty())
    }

    @Test fun cropCapturesFreshNativePixelsAndInputUsesRegionOffset(): Unit = runBlocking {
        val desktop = FakeComputerDesktop().apply { nativeScale = 2 }
        val computer = testComputer(desktop)
        computer.enableForTest("a", ComputerAccess.CONTROL)
        val epoch = computer.grant("a")!!
        val overview = computer.execute("a", epoch, request("screenshot"))
        val detail = computer.execute("a", epoch, crop(overview.screenshotId(), 400, 200, 500, 250))
        assertFalse(detail.failed())
        assertEquals(2, desktop.captures, "Crop must capture the display again, not enlarge the old image")
        assertEquals(DesktopCaptureRequest(DesktopRegion(640, 320, 800, 400), ScreenshotResolution.NATIVE), desktop.captureRequests.last())
        assertEquals(JsonPrimitive(1600), detail.metadata()["width"])
        assertEquals(JsonPrimitive(800), detail.metadata()["height"])
        val after = computer.execute("a", epoch, request("drag") {
            put("screenshot_id", detail.screenshotId()); put("x", 100); put("y", 50); put("to_x", 1599); put("to_y", 799)
        })
        assertFalse(after.failed())
        assertEquals(ComputerAction("drag", 690, 345, 1439, 719), desktop.performed.single())
        assertEquals(DesktopRegion.full(desktop.monitors.single()), desktop.captureRequests.last().region)
        assertEquals(ScreenshotResolution.OVERVIEW, desktop.captureRequests.last().resolution)
        assertTrue(computer.execute("a", epoch, crop(detail.screenshotId(), 0, 0, 10, 10)).failed())
    }

    @Test fun nestedCropAndItsClickKeepTheOriginalDisplayCoordinates(): Unit = runBlocking {
        val desktop = FakeComputerDesktop().apply { nativeScale = 2 }
        val computer = testComputer(desktop)
        computer.enableForTest("a", ComputerAccess.CONTROL)
        val epoch = computer.grant("a")!!
        val overview = computer.execute("a", epoch, request("screenshot"))
        val first = computer.execute("a", epoch, crop(overview.screenshotId(), 400, 200, 500, 250))
        val second = computer.execute("a", epoch, crop(first.screenshotId(), 600, 100, 200, 200))
        assertFalse(second.failed())
        assertEquals(DesktopRegion(940, 370, 100, 100), desktop.captureRequests.last().region)
        assertFalse(computer.execute("a", epoch, request("click") {
            put("screenshot_id", second.screenshotId()); put("x", 100); put("y", 100)
        }).failed())
        assertEquals(ComputerAction("click", 990, 420), desktop.performed.single())
    }

    @Test fun invalidRegionsAndScreenshotOptionsAreRejectedBeforeCaptureOrInput(): Unit = runBlocking {
        val desktop = FakeComputerDesktop()
        val computer = testComputer(desktop)
        computer.enableForTest("a", ComputerAccess.CONTROL)
        val epoch = computer.grant("a")!!
        val id = computer.execute("a", epoch, request("screenshot")).screenshotId()
        val invalid = listOf(crop(id, -1, 0, 10, 10), crop(id, 0, 0, 0, 10), crop(id, 1590, 0, 11, 10),
            crop(id, 0, 0, Int.MAX_VALUE, 1), crop("wrong", 0, 0, 10, 10),
            request("screenshot") { put("resolution", "huge") }, request("screenshot") { put("region", "0,0,10,10") },
            JsonObject(crop(id, 0, 0, 10, 10) + ("display_id" to JsonPrimitive("different"))),
            JsonObject(crop(id, 0, 0, 10, 10) + ("action" to JsonPrimitive("click"))),
            request("click") { put("screenshot_id", id); put("x", 0); put("y", 0); put("resolution", "native") })
        for (args in invalid) assertTrue(computer.execute("a", epoch, args).failed(), args.toString())
        assertEquals(1, desktop.captures)
        assertTrue(desktop.performed.isEmpty())
    }

    @Test fun staleOrReconfiguredRegionCannotCaptureAnotherDisplay(): Unit = runBlocking {
        var now = 1L
        val desktop = FakeComputerDesktop()
        val computer = testComputer(desktop) { now }
        computer.enableForTest("a", ComputerAccess.SCREEN)
        val epoch = computer.grant("a")!!
        val id = computer.execute("a", epoch, request("screenshot")).screenshotId()
        now += 30_000_000_001L
        assertTrue(computer.execute("a", epoch, crop(id, 0, 0, 10, 10)).failed())
        now = 2L
        desktop.monitors = desktop.monitors.map { it.copy(x = 0) }
        assertTrue(computer.execute("a", epoch, crop(id, 0, 0, 10, 10)).failed())
        assertEquals(1, desktop.captures)
    }

    @Test fun nativeEncodingPreservesFinePixelPatternRatherThanUpscalingOverview() {
        val source = BufferedImage(2400, 20, BufferedImage.TYPE_INT_RGB)
        try {
            for (y in 0 until source.height) for (x in 0 until source.width) source.setRGB(x, y, if (x % 2 == 0) 0xffffff else 0)
            val native = encodeDesktopCapture(source, ScreenshotResolution.NATIVE)
            val overview = encodeDesktopCapture(source, ScreenshotResolution.OVERVIEW)
            assertEquals(2400, native.width); assertEquals(1600, overview.width)
            val decoded = ImageIO.read(native.png.inputStream())
            try { for (x in 0 until source.width) assertEquals(source.getRGB(x, 10), decoded.getRGB(x, 10)) }
            finally { decoded.flush() }
        } finally { source.flush() }
    }
}
