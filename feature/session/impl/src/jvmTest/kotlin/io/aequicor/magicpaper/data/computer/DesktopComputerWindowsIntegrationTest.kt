package io.aequicor.magicpaper.data.computer

import io.aequicor.magicpaper.ui.window.paperWindowIgnoreMouse
import java.awt.Color
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.InputEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Opt-in interactive Windows desktop only. No app runtime, user history or saved desktop images. */
class DesktopComputerWindowsIntegrationTest {
    @Test fun captureAndInputExcludeOwnWindowsAndRestoreUserControls() {
        assumeTrue(System.getProperty("magicpaper.computer.windows.native") == "true")
        assertTrue(System.getProperty("os.name").startsWith("Windows"), "Run this acceptance check on Windows")
        val directory = Files.createTempDirectory("computer-windows-fixture-")
        val desktop = AwtComputerDesktop()
        val pointer = java.awt.MouseInfo.getPointerInfo().location
        val robot = Robot().apply { autoDelay = 50 }
        var process: Process? = null
        var own: JFrame? = null
        var overlay: JFrame? = null
        try {
            val source = directory.resolve("DesktopWindowsFixture.java")
            Files.write(source, checkNotNull(javaClass.getResourceAsStream("/computer/DesktopWindowsFixture.java")).use { it.readBytes() })
            NativeApplicationDesktop(launchProcess = {
                ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), source.toString())
                    .redirectError(directory.resolve("fixture.log").toFile()).start().also { process = it }
            }).use { fixture ->
                val info = fixture.request(request("state")) {}
                fun number(key: String) = info[key]!!.jsonPrimitive.int
                val bounds = Rectangle(number("x"), number("y"), number("width"), number("height"))
                val x = bounds.x + bounds.width / 2
                val y = bounds.y + bounds.height / 2
                fun click() {
                    robot.mouseMove(x, y)
                    try { robot.mousePress(InputEvent.BUTTON1_DOWN_MASK) }
                    finally { robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
                }
                fun clicks() = fixture.request(request("state")) {}["clicks"]!!.jsonPrimitive.int
                fun awaitCondition(label: String, predicate: () -> Boolean) {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (!predicate()) {
                        assertTrue(System.nanoTime() < deadline, label)
                        Thread.sleep(50)
                    }
                }
                awaitCondition("External fixture must be visible") { robot.getPixelColor(x, y).blue > 180 }
                click()
                awaitCondition("Positive control: input must reach the external process") { clicks() == 1 }

                val ownClicks = AtomicInteger()
                SwingUtilities.invokeAndWait {
                    own = JFrame("MagicPaper excluded Windows fixture").apply {
                        isUndecorated = true; setBounds(bounds)
                        add(object : JButton() {
                            override fun paintComponent(graphics: java.awt.Graphics) {
                                graphics.color = Color.MAGENTA
                                graphics.fillRect(0, 0, width, height)
                            }
                        }.apply {
                            background = Color.MAGENTA; isOpaque = true; isBorderPainted = false; isFocusPainted = false
                            addActionListener { ownClicks.incrementAndGet() }
                        })
                        isAlwaysOnTop = true; isAutoRequestFocus = false; isVisible = true
                    }
                }
                awaitCondition("Positive control: own window must occlude the external fixture") {
                    robot.getPixelColor(x, y).let { it.red > 180 && it.blue > 180 && it.green < 80 }
                }
                val display = desktop.displays().first()
                fun assertCapture(options: DesktopCaptureRequest = DesktopCaptureRequest(DesktopRegion.full(display))) {
                    val capture = desktop.capture(display, options)
                    val image = ImageIO.read(capture.png.inputStream())
                    try {
                        val region = options.region
                        val px = ((x - display.x - region.x).toDouble() * image.width / region.width).toInt()
                        val py = ((y - display.y - region.y).toDouble() * image.height / region.height).toInt()
                        if (options.resolution == ScreenshotResolution.NATIVE) {
                            val density = own!!.graphicsConfiguration.defaultTransform
                            // Fractional Windows scaling rounds a logical edge to a physical pixel.
                            assertEquals(region.width * density.scaleX, image.width.toDouble(), 1.0)
                            assertEquals(region.height * density.scaleY, image.height.toDouble(), 1.0)
                        }
                        val color = Color(image.getRGB(px, py))
                        assertTrue(color.blue > 180 && color.red < 80 && color.green < 80,
                            "Capture must show the external blue fixture, not an own window or black rectangle: $color")
                    } finally { image.flush() }
                    SwingUtilities.invokeAndWait { assertTrue(own!!.isShowing); assertTrue(own!!.isAlwaysOnTop) }
                }
                assertCapture() // Ordinary opaque window (GetWindowDisplayAffinity may be unavailable).
                SwingUtilities.invokeAndWait {
                    overlay = JFrame("MagicPaper feedback Windows fixture").apply {
                        name = "MagicPaperComputerOverlay"
                        isUndecorated = true; setBounds(bounds)
                        contentPane.background = Color.GREEN
                        focusableWindowState = false; isAutoRequestFocus = false
                        isAlwaysOnTop = true; isVisible = true
                        paperWindowIgnoreMouse(this, true)
                    }
                }
                awaitCondition("Layered feedback fixture must be visible") { robot.getPixelColor(x, y).green > 180 }
                assertCapture() // Includes the persistent layered, mouse-transparent effect window.
                assertCapture(DesktopCaptureRequest(DesktopRegion(x - display.x - 20, y - display.y - 15, 40, 30), ScreenshotResolution.NATIVE))
                desktop.perform(ComputerAction("click", x - display.x, y - display.y), display) {}
                awaitCondition("Agent click must pass through both own windows to another process") { clicks() == 2 }
                assertEquals(0, ownClicks.get())
                click() // Input restoration keeps the user's Stop/chat controls usable.
                awaitCondition("Own controls must receive user clicks after the agent action") { ownClicks.get() == 1 }
                assertEquals(2, clicks())

                val cancelled = kotlinx.coroutines.CancellationException("fixture stop")
                assertSame(cancelled, assertFailsWith<kotlinx.coroutines.CancellationException> {
                    desktop.perform(ComputerAction("click", x - display.x, y - display.y), display) { throw cancelled }
                })
                click()
                awaitCondition("Cancellation must also restore own input") { ownClicks.get() == 2 }
                assertEquals(2, clicks())
                val report = Path.of("build/reports/computer-use/windows-native.txt")
                Files.createDirectories(report.parent)
                Files.writeString(report, "PASS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}; " +
                    "opaque/layered exclusion, native-density region, external-process clicks, own input restoration and cancellation. " +
                    "Desktop images were inspected in memory only.\n")
            }
        } finally {
            desktop.close()
            SwingUtilities.invokeAndWait { overlay?.dispose(); own?.dispose() }
            robot.mouseMove(pointer.x, pointer.y)
            process?.let { it.destroyForcibly(); check(it.waitFor(5, TimeUnit.SECONDS)) { "Fixture process did not exit" } }
            directory.toFile().deleteRecursively()
        }
    }
}
