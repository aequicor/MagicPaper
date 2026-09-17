package io.aequicor.magicpaper.data.computer

import java.awt.Color
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Local fixture pixels only; the desktop image is inspected in memory, never persisted or sent to a model. */
class DesktopCaptureMacIntegrationTest {
    @Test fun nativeDisplayCaptureExcludesOwnAlwaysOnTopWindowAndShowsUnderlyingFixture() {
        assumeTrue(System.getProperty("magicpaper.application.native") == "true")
        assumeTrue(System.getProperty("os.name").startsWith("Mac"))
        val directory = Files.createTempDirectory("computer-capture-fixture-")
        try {
            NativeApplicationDesktop(Path.of(System.getProperty("magicpaper.application.testInstallation"))).use { native ->
                assertEquals(JsonPrimitive(true), native.request(request("permissions")) {}["screen_capture"],
                    "Screen Recording permission is required; no permission prompt is triggered by this test")
                val source = directory.resolve("fixture.swift")
                Files.write(source, checkNotNull(javaClass.getResourceAsStream("/computer/application-fixture.swift")).use { it.readBytes() })
                val binary = directory.resolve("fixture")
                val compiler = ProcessBuilder("xcrun", "swiftc", source.toString(), "-o", binary.toString())
                    .redirectErrorStream(true).redirectOutput(directory.resolve("compiler.log").toFile()).start()
                try { assertTrue(compiler.waitFor(45, TimeUnit.SECONDS)); assertEquals(0, compiler.exitValue()) }
                finally { compiler.destroyForcibly() }
                NativeApplicationDesktop(launchProcess = { ProcessBuilder(binary.toString(), "Capture fixture").start() }).use { fixture ->
                    val bounds = fixture.request(request("reveal")) {}
                    fun coordinate(key: String) = bounds[key]!!.jsonPrimitive.double.toInt()
                    var own: JFrame? = null
                    try {
                        SwingUtilities.invokeAndWait {
                            own = JFrame("MagicPaper excluded fixture").apply {
                                isUndecorated = true; background = Color.MAGENTA
                                contentPane.background = Color.MAGENTA
                                setBounds(coordinate("x"), coordinate("y"), coordinate("width"), coordinate("height"))
                                isAlwaysOnTop = true; isAutoRequestFocus = false; isVisible = true
                            }
                        }
                        val centerX = coordinate("x") + coordinate("width") / 2
                        val centerY = coordinate("y") + coordinate("height") / 2
                        val robot = java.awt.Robot()
                        var visible = robot.getPixelColor(centerX, centerY)
                        repeat(20) {
                            if (visible.red < 180 || visible.blue < 180) { Thread.sleep(100); visible = robot.getPixelColor(centerX, centerY) }
                        }
                        assertTrue(visible.red > 180 && visible.blue > 180, "Positive control: the app's magenta window must actually occlude the fixture")
                        val display = AwtComputerDesktop().displays().first()
                        val capture = native.request(request("desktop_capture") {
                            put("x", display.x); put("y", display.y); put("width", display.width); put("height", display.height)
                        }) {}
                        val image = ImageIO.read(Base64.getDecoder().decode(capture.requiredString("png")).inputStream())
                        val x = ((coordinate("x") + coordinate("width") / 2 - display.x).toDouble() * image.width / display.width).toInt()
                        val y = ((coordinate("y") + coordinate("height") / 2 - display.y).toDouble() * image.height / display.height).toInt()
                        val color = Color(image.getRGB(x, y))
                        assertTrue(color.blue > 180 && color.red < 80, "Expected the blue external fixture beneath the excluded magenta app window; got $color")
                        val density = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.defaultTransform
                        val detail = native.request(request("desktop_capture") {
                            put("x", display.x); put("y", display.y); put("width", display.width); put("height", display.height)
                            put("resolution", "native")
                            put("region_x", centerX - display.x - 20); put("region_y", centerY - display.y - 15)
                            put("region_width", 40); put("region_height", 30)
                        }) {}
                        val cropped = ImageIO.read(Base64.getDecoder().decode(detail.requiredString("png")).inputStream())
                        try {
                            assertEquals((40 * density.scaleX).toInt(), cropped.width, "Region must retain native display density")
                            assertEquals((30 * density.scaleY).toInt(), cropped.height)
                            val pixel = Color(cropped.getRGB(cropped.width / 2, cropped.height / 2))
                            assertTrue(pixel.blue > 180 && pixel.red < 80, "Native region must reveal the same underlying fixture")
                        } finally { cropped.flush() }
                        val nativeFull = native.request(request("desktop_capture") {
                            put("x", display.x); put("y", display.y); put("width", display.width); put("height", display.height)
                            put("resolution", "native")
                        }) {}
                        assertEquals((display.width * density.scaleX).toInt(), nativeFull["width"]!!.jsonPrimitive.int)
                        assertEquals((display.height * density.scaleY).toInt(), nativeFull["height"]!!.jsonPrimitive.int)
                        Files.createDirectories(Path.of("build/reports/computer-use"))
                        Files.writeString(Path.of("build/reports/computer-use/native-capture.txt"),
                            "PASS: ScreenCaptureKit excludes parent JVM windows in overview and native region; underlying blue fixture visible. " +
                                "Overview ${image.width}x${image.height}; native full ${nativeFull["width"]}x${nativeFull["height"]}; region ${detail["width"]}x${detail["height"]}. Desktop images not retained.\n")
                        image.flush()
                    } finally { SwingUtilities.invokeAndWait { own?.dispose() } }
                }
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}
