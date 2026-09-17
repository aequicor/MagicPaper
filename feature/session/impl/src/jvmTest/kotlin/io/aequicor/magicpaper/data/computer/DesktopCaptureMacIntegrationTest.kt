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
                        Files.createDirectories(Path.of("build/reports/computer-use"))
                        Files.writeString(Path.of("build/reports/computer-use/native-capture.txt"),
                            "PASS: ScreenCaptureKit display capture excludes parent JVM windows; underlying blue fixture visible. Image ${image.width}x${image.height}. Desktop image not retained.\n")
                        image.flush()
                    } finally { SwingUtilities.invokeAndWait { own?.dispose() } }
                }
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}
