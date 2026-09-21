package io.aequicor.magicpaper.data.computer

import java.awt.event.KeyEvent
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.domain.ComputerAccess
import kotlin.test.*

class ScreenshotEncodingTest {
    @Test fun pngIsLosslessAndJpegKeepsDimensions() {
        val desktop = FakeComputerDesktop()
        val original = desktop.capture(desktop.displays().first())
        val exact = encodeScreenshot(original.png, "png")
        assertContentEquals(original.png, exact.bytes)
        val jpeg = encodeScreenshot(original.png, "jpeg")
        assertEquals("image/jpeg", jpeg.mimeType)
        val decoded = ImageIO.read(jpeg.bytes.inputStream())
        assertEquals(original.width, decoded.width)
        assertEquals(original.height, decoded.height)
    }

    @Test fun explicitPngAndVisualCoordinatesStayConsistentAndRevocationClearsActivity() = runBlocking {
        val computer = testComputer(FakeComputerDesktop())
        computer.enableForTest("fixture", ComputerAccess.CONTROL)
        val epoch = computer.grant("fixture")!!
        val before = computer.execute("fixture", epoch, request("screenshot") { put("format", "png") })
        assertEquals("image/png", before["content"]!!.jsonArray[1].jsonObject.requiredString("mimeType"))
        assertTrue(computer.state.value.desktopActive)
        computer.execute("fixture", epoch, request("click") {
            put("screenshot_id", before.screenshotId()); put("x", 800); put("y", 450)
        })
        val activity = computer.state.value.activity!!
        assertEquals(-2560, activity.displayX)
        assertEquals(1280, activity.cursorX)
        assertEquals(720, activity.cursorY)
        computer.disable()
        assertFalse(computer.state.value.desktopActive)
        assertNull(computer.state.value.activity)
    }

    @Test fun symbolKeysAreExplicitAndErrorsGiveSupportedAlternatives() {
        assertEquals(listOf(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_EQUALS), ComputerKeys.parse(listOf("CTRL", "PLUS")))
        assertEquals(listOf(KeyEvent.VK_ADD), ComputerKeys.parse(listOf("ADD")))
        assertEquals(listOf(KeyEvent.VK_EQUALS), ComputerKeys.parse(listOf("OEM_PLUS")))
        assertContains(assertFailsWith<IllegalArgumentException> { ComputerKeys.parse(listOf("UNSUPPORTED")) }.message.orEmpty(), "Для символов используйте type")
    }
}
