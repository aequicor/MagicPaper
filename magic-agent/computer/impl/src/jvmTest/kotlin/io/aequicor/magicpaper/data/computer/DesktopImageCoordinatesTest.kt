package io.aequicor.magicpaper.data.computer

import io.aequicor.magicpaper.domain.ComputerAccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopImageCoordinatesTest {
    private fun imageSize(width: Int, height: Int) = buildJsonObject {
        put("width", width); put("height", height)
    }
    private fun region(x: Int, y: Int, width: Int, height: Int) = buildJsonObject {
        put("x", x); put("y", y); put("width", width); put("height", height)
    }

    @Test fun allPointerActionsMapResizedOverviewPixelsBeforeDesktopInput(): Unit = runBlocking {
        val desktop = FakeComputerDesktop()
        val computer = testComputer(desktop)
        computer.enableForTest("a", ComputerAccess.CONTROL)
        val epoch = computer.grant("a")!!
        for (action in listOf("click", "double_click", "move", "drag", "scroll")) {
            val shot = computer.execute("a", epoch, request("screenshot"))
            // The 2560×1440 display is returned at 1600×900 and viewed at 800×450.
            val after = computer.execute("a", epoch, request(action) {
                put("screenshot_id", shot.screenshotId()); put("image_size", imageSize(800, 450))
                put("x", 600); put("y", 225)
                if (action == "drag") { put("to_x", 799); put("to_y", 449) }
                if (action == "scroll") put("amount", 2)
            })
            assertFalse(after.failed(), action)
            val input = desktop.performed.last()
            assertEquals(1920, input.x); assertEquals(720, input.y)
            if (action == "drag") { assertEquals(2556, input.toX); assertEquals(1436, input.toY) }
            val activity = computer.state.value.activity!!
            assertEquals(-2560, activity.displayX, "Monitor offset stays separate from local input, including left-hand displays")
            assertEquals(if (action == "drag") 2556 else 1920, activity.cursorX)
        }
        assertEquals(5, desktop.performed.size)
    }

    @Test fun resizedNativeImageMapsToLogicalPixelsAndOverrideDoesNotLeakToNextFrame(): Unit = runBlocking {
        val desktop = FakeComputerDesktop().apply {
            monitors = listOf(ComputerDisplay("retina", 1920, 1080)); nativeScale = 2
        }
        val computer = testComputer(desktop)
        computer.enableForTest("a", ComputerAccess.CONTROL)
        val epoch = computer.grant("a")!!
        val shot = computer.execute("a", epoch, request("screenshot") { put("resolution", "native") })
        // Original 3840×2160 pixels, measured in a 960×540 view.
        val after = computer.execute("a", epoch, request("click") {
            put("screenshot_id", shot.screenshotId()); put("image_size", imageSize(960, 540))
            put("x", 480); put("y", 270)
        })
        assertFalse(after.failed())
        assertEquals(ComputerAction("click", 960, 540), desktop.performed.single())
        val metadata = Json.parseToJsonElement(after["content"]!!.jsonArray[0].jsonObject.requiredString("text")).jsonObject
        assertEquals(imageSize(1600, 900), metadata["image_size"])
        assertFalse(computer.execute("a", epoch, request("click") {
            put("screenshot_id", after.screenshotId()); put("x", 800); put("y", 450)
        }).failed())
        assertEquals(ComputerAction("click", 960, 540), desktop.performed.last())
    }

    @Test fun resizedRegionsCanBeNestedAndDragUsesTheirOriginalScreenOffsets(): Unit = runBlocking {
        val desktop = FakeComputerDesktop().apply { nativeScale = 2 }
        val computer = testComputer(desktop)
        computer.enableForTest("a", ComputerAccess.CONTROL)
        val epoch = computer.grant("a")!!
        val overview = computer.execute("a", epoch, request("screenshot"))
        val detail = computer.execute("a", epoch, request("screenshot") {
            put("screenshot_id", overview.screenshotId()); put("image_size", imageSize(800, 450))
            put("region", region(200, 100, 250, 125))
        })
        assertFalse(detail.failed())
        assertEquals(DesktopRegion(640, 320, 800, 400), desktop.captureRequests.last().region)
        // Native crop 1600×800 is viewed at 400×200, then narrowed again.
        val nested = computer.execute("a", epoch, request("screenshot") {
            put("screenshot_id", detail.screenshotId()); put("image_size", imageSize(400, 200))
            put("region", region(150, 25, 50, 50))
        })
        assertFalse(nested.failed())
        assertEquals(DesktopRegion(940, 370, 100, 100), desktop.captureRequests.last().region)
        assertFalse(computer.execute("a", epoch, request("drag") {
            put("screenshot_id", nested.screenshotId()); put("image_size", imageSize(50, 50))
            put("x", 25); put("y", 25); put("to_x", 49); put("to_y", 49)
        }).failed())
        assertEquals(ComputerAction("drag", 990, 420, 1038, 468), desktop.performed.single())
        assertTrue(computer.execute("a", epoch, request("click") {
            put("screenshot_id", nested.screenshotId()); put("image_size", imageSize(50, 50)); put("x", 25); put("y", 25)
        }).failed(), "Resizing must not revive a consumed screenshot")
        assertEquals(1, desktop.performed.size)
    }

    @Test fun nonIntegralScaleMapsDirectlyWithoutRoundingThroughEncodedImage(): Unit = runBlocking {
        val desktop = FakeComputerDesktop().apply { monitors = listOf(ComputerDisplay("4k", 3840, 2160)) }
        val computer = testComputer(desktop)
        computer.enableForTest("a", ComputerAccess.CONTROL)
        val epoch = computer.grant("a")!!
        val shot = computer.execute("a", epoch, request("screenshot"))
        assertFalse(computer.execute("a", epoch, request("drag") {
            put("screenshot_id", shot.screenshotId()); put("image_size", imageSize(1000, 563))
            put("x", 1); put("y", 1); put("to_x", 999); put("to_y", 562)
        }).failed())
        assertEquals(ComputerAction("drag", 3, 3, 3836, 2156), desktop.performed.single())
    }

    @Test fun malformedSizesAndCoordinatesAreRejectedWithoutConsumingTheFrame(): Unit = runBlocking {
        val desktop = FakeComputerDesktop()
        val computer = testComputer(desktop)
        computer.enableForTest("a", ComputerAccess.CONTROL)
        val epoch = computer.grant("a")!!
        val id = computer.execute("a", epoch, request("screenshot")).screenshotId()
        val invalidSizes = listOf(JsonPrimitive("800x450"), JsonArray(emptyList()),
            imageSize(0, 450), imageSize(800, -1), buildJsonObject { put("width", 800) },
            buildJsonObject { put("width", "800"); put("height", 450) },
            buildJsonObject { put("width", 800.5); put("height", 450) },
            buildJsonObject { put("width", 800); put("height", 450); put("x", 10) })
        for (size in invalidSizes) assertTrue(computer.execute("a", epoch, request("click") {
            put("screenshot_id", id); put("image_size", size); put("x", 0); put("y", 0)
        }).failed(), size.toString())
        val invalidActions = listOf(
            request("click") { put("x", 800); put("y", 0) },
            request("move") { put("x", 0); put("y", -1) },
            request("scroll") { put("x", 0); put("y", 450); put("amount", 1) },
            request("drag") { put("x", 0); put("y", 0); put("to_x", 800); put("to_y", 0) },
            request("screenshot") { put("region", region(799, 0, 2, 10)) },
            request("screenshot"), request("wait"), request("displays"),
            request("type") { put("text", "test") }, request("key") { put("keys", buildJsonArray { add("ENTER") }) })
        for (args in invalidActions) assertTrue(computer.execute("a", epoch, JsonObject(args + mapOf(
            "screenshot_id" to JsonPrimitive(id), "image_size" to imageSize(800, 450)))).failed(), args.toString())
        assertEquals(1, desktop.captures)
        assertTrue(desktop.performed.isEmpty())
        assertFalse(computer.execute("a", epoch, request("click") {
            put("screenshot_id", id); put("image_size", imageSize(800, 450)); put("x", 400); put("y", 225)
        }).failed())
        assertEquals(ComputerAction("click", 1280, 720), desktop.performed.single())
    }
}
