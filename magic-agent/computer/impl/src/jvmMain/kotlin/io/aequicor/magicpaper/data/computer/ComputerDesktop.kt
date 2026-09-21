package io.aequicor.magicpaper.data.computer

import io.aequicor.magicpaper.domain.ComputerAccess
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import com.sun.jna.NativeLibrary
import kotlinx.serialization.json.*

internal data class ComputerDisplay(val id: String, val width: Int, val height: Int, val x: Int = 0, val y: Int = 0)
internal data class DesktopCapture(val png: ByteArray, val width: Int, val height: Int)

/** Blocking desktop operations; the service serializes them on Dispatchers.IO. */
internal interface ComputerDesktop {
    val supported: Boolean
    fun checkPermissions(access: ComputerAccess, request: Boolean = false)
    fun displays(): List<ComputerDisplay>
    fun capture(display: ComputerDisplay,
        request: DesktopCaptureRequest = DesktopCaptureRequest(DesktopRegion.full(display)),
        checkActive: () -> Unit = {}): DesktopCapture
    fun close() = Unit
    fun perform(action: ComputerAction, display: ComputerDisplay, checkActive: () -> Unit)
}

internal data class ComputerAction(
    val kind: String,
    val x: Int = 0, val y: Int = 0,
    val toX: Int = 0, val toY: Int = 0,
    val button: String = "left",
    val amount: Int = 0,
    val text: String = "",
    val keys: List<Int> = emptyList(),
)

internal class AwtComputerDesktop : ComputerDesktop {
    private var captureAdapter: NativeApplicationDesktop? = null
    @Synchronized private fun captureAdapter() = captureAdapter ?: NativeApplicationDesktop().also { captureAdapter = it }
    @Synchronized override fun close() { captureAdapter?.close(); captureAdapter = null }
    private val mac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    // AWT Robot cannot reliably control a Wayland desktop. Do not silently control XWayland alone.
    override val supported: Boolean get() = !GraphicsEnvironment.isHeadless() &&
        !(System.getProperty("os.name").startsWith("Linux") && !System.getenv("WAYLAND_DISPLAY").isNullOrBlank())

    override fun checkPermissions(access: ComputerAccess, request: Boolean) {
        if (!supported) throw ComputerPermissionException(ComputerPermissionFailure.UNSUPPORTED)
        if (!mac) return
        val cg = NativeLibrary.getInstance("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics")
        fun canCapture() = cg.getFunction("CGPreflightScreenCaptureAccess").invokeInt(emptyArray()) and 0xff != 0
        if (!NativeApplicationDesktop.supported && !canCapture()) {
            if (request) cg.getFunction("CGRequestScreenCaptureAccess").invokeInt(emptyArray())
            if (!canCapture()) throw ComputerPermissionException(ComputerPermissionFailure.SCREEN_RECORDING)
        }
        if (access == ComputerAccess.CONTROL) {
            val ax = NativeLibrary.getInstance("/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices")
            if (ax.getFunction("AXIsProcessTrusted").invokeInt(emptyArray()) and 0xff == 0)
                throw ComputerPermissionException(ComputerPermissionFailure.ACCESSIBILITY)
        }
    }

    override fun displays(): List<ComputerDisplay> {
        val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
        return env.screenDevices.sortedBy { it != env.defaultScreenDevice }.map { device ->
            val bounds = device.defaultConfiguration.bounds
            ComputerDisplay(device.iDstring, bounds.width, bounds.height, bounds.x, bounds.y)
        }
    }

    private fun device(display: ComputerDisplay) = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        .firstOrNull { it.iDstring == display.id } ?: error("Экран отключён. Сделайте новый снимок.")

    override fun capture(display: ComputerDisplay, request: DesktopCaptureRequest, checkActive: () -> Unit): DesktopCapture {
        checkActive()
        val region = request.region.also { it.validate(display) }
        if (mac && NativeApplicationDesktop.supported) {
            val result = try { captureAdapter().request(buildJsonObject {
                    put("action", "desktop_capture"); put("x", display.x); put("y", display.y)
                    put("width", display.width); put("height", display.height)
                    put("resolution", request.resolution.wireName)
                    put("region_x", region.x); put("region_y", region.y)
                    put("region_width", region.width); put("region_height", region.height)
                }, checkActive)
            } catch (error: Exception) {
                try { close() } catch (cleanup: Throwable) {
                    val primary = combineComputerCleanup(error, cleanup)
                    throw primary
                }
                throw error
            }
            return DesktopCapture(java.util.Base64.getDecoder().decode(result.requiredString("png")),
                result["width"]!!.jsonPrimitive.int, result["height"]!!.jsonPrimitive.int)
        }
        val device = device(display)
        val transform = device.defaultConfiguration.defaultTransform
        require(request.resolution != ScreenshotResolution.NATIVE ||
            region.width.toDouble() * transform.scaleX * region.height * transform.scaleY <= MAX_NATIVE_SCREENSHOT_PIXELS) {
            "Слишком большой снимок. Запросите screenshot с region для нужной области."
        }
        val variants = withOwnWindowsExcludedFromCapture {
            checkActive()
            val bounds = Rectangle(display.x + region.x, display.y + region.y, region.width, region.height)
            val robot = Robot(device)
            if (request.resolution == ScreenshotResolution.NATIVE) robot.createMultiResolutionScreenCapture(bounds).resolutionVariants
            else listOf(robot.createScreenCapture(bounds))
        }
        try {
            checkActive()
            return encodeDesktopCapture(variants.maxBy { it.getWidth(null).toLong() * it.getHeight(null) }, request.resolution)
        } finally { variants.forEach { it.flush() } }
    }

    override fun perform(action: ComputerAction, display: ComputerDisplay, checkActive: () -> Unit) {
        if (action.kind in listOf("type", "key")) check(Window.getWindows().none { it.isFocused }) {
            "Фокус находится в MagicPaper. Сделайте свежий screenshot и сначала нажмите на целевое окно."
        }
        val inputBounds = if (action.kind in listOf("key", "type")) null else {
            val endX = if (action.kind == "drag") action.toX else action.x
            val endY = if (action.kind == "drag") action.toY else action.y
            Rectangle(display.x + minOf(action.x, endX), display.y + minOf(action.y, endY),
                kotlin.math.abs(endX - action.x) + 1, kotlin.math.abs(endY - action.y) + 1)
        }
        withOwnWindowsIgnoringMouse(inputBounds) { performInput(action, display, checkActive) }
    }

    private fun performInput(action: ComputerAction, display: ComputerDisplay, checkActive: () -> Unit) {
        val robot = Robot(device(display)).apply { autoDelay = 25 }
        val button = when (action.button) {
            "right" -> InputEvent.BUTTON3_DOWN_MASK
            "middle" -> InputEvent.BUTTON2_DOWN_MASK
            else -> InputEvent.BUTTON1_DOWN_MASK
        }
        fun move(x: Int, y: Int) { checkActive(); robot.mouseMove(display.x + x, display.y + y) }
        fun chord(keys: List<Int>) {
            val pressed = mutableListOf<Int>()
            try { keys.forEach { checkActive(); robot.keyPress(it); pressed += it } }
            finally { pressed.asReversed().forEach { robot.keyRelease(it) } }
        }
        checkActive()
        when (action.kind) {
            "move" -> move(action.x, action.y)
            "click", "double_click" -> {
                move(action.x, action.y)
                repeat(if (action.kind == "double_click") 2 else 1) {
                    checkActive()
                    try { robot.mousePress(button) } finally { robot.mouseRelease(button) }
                }
            }
            "drag" -> {
                move(action.x, action.y)
                checkActive()
                try {
                    robot.mousePress(button)
                    for (step in 1..20) move(action.x + (action.toX - action.x) * step / 20, action.y + (action.toY - action.y) * step / 20)
                } finally { robot.mouseRelease(button) }
            }
            "scroll" -> { move(action.x, action.y); checkActive(); robot.mouseWheel(action.amount) }
            "key" -> chord(action.keys)
            "type" -> {
                // Clipboard paste supports Cyrillic/Unicode without assuming a keyboard layout.
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                val previous = clipboard.getContents(null)
                val temporary = StringSelection(action.text)
                checkActive()
                clipboard.setContents(temporary, temporary)
                try {
                    chord(listOf(if (mac) KeyEvent.VK_META else KeyEvent.VK_CONTROL, KeyEvent.VK_V))
                    robot.delay(150)
                } finally {
                    // Do not overwrite a new clipboard value supplied by the user meanwhile.
                    val current = clipboard.getContents(null)
                    if (current?.isDataFlavorSupported(DataFlavor.stringFlavor) == true &&
                        runCatching { current.getTransferData(DataFlavor.stringFlavor) }.getOrNull() == action.text) {
                        clipboard.setContents(previous ?: StringSelection(""), null)
                    }
                }
            }
            else -> error("Неизвестное действие")
        }
    }
}

internal object ComputerKeys {
    fun parse(names: List<String>): List<Int> {
        require(names.isNotEmpty() && names.size <= 6) { "keys: от 1 до 6 клавиш" }
        val codes = names.flatMap { name ->
            if (name.uppercase() in listOf("PLUS", "+")) return@flatMap listOf(KeyEvent.VK_SHIFT, KeyEvent.VK_EQUALS)
            listOf(
            when (val key = name.uppercase()) {
                "CTRL", "CONTROL" -> KeyEvent.VK_CONTROL
                "CMD", "META", "COMMAND", "SUPER" -> KeyEvent.VK_META
                "WIN" -> KeyEvent.VK_WINDOWS
                "ALT", "OPTION" -> KeyEvent.VK_ALT
                "SHIFT" -> KeyEvent.VK_SHIFT
                "ENTER", "RETURN" -> KeyEvent.VK_ENTER
                "ESC", "ESCAPE" -> KeyEvent.VK_ESCAPE
                "TAB" -> KeyEvent.VK_TAB
                "SPACE" -> KeyEvent.VK_SPACE
                "BACKSPACE" -> KeyEvent.VK_BACK_SPACE
                "DELETE" -> KeyEvent.VK_DELETE
                "UP" -> KeyEvent.VK_UP
                "DOWN" -> KeyEvent.VK_DOWN
                "LEFT" -> KeyEvent.VK_LEFT
                "RIGHT" -> KeyEvent.VK_RIGHT
                "HOME" -> KeyEvent.VK_HOME
                "END" -> KeyEvent.VK_END
                "PAGEUP" -> KeyEvent.VK_PAGE_UP
                "PAGEDOWN" -> KeyEvent.VK_PAGE_DOWN
                "ADD", "NUMPAD_ADD" -> KeyEvent.VK_ADD
                "EQUALS", "OEM_PLUS", "=" -> KeyEvent.VK_EQUALS
                "MINUS", "OEM_MINUS", "-" -> KeyEvent.VK_MINUS
                else -> when {
                    key.length == 1 && (key[0] in 'A'..'Z' || key[0] in '0'..'9') -> key[0].code
                    key.matches(Regex("F([1-9]|1[0-2])")) -> KeyEvent.VK_F1 + key.drop(1).toInt() - 1
                    else -> throw IllegalArgumentException("Неизвестная клавиша. Используйте CMD, CTRL, ALT, SHIFT, ENTER, TAB, ESC, SPACE, BACKSPACE, DELETE, стрелки, HOME, END, PAGEUP, PAGEDOWN, A–Z, 0–9, F1–F12, PLUS, MINUS, EQUALS или ADD. Для символов используйте type.")
                }
            })
        }
        require(codes.distinct().size == codes.size) { "Повторяющиеся клавиши" }
        return codes
    }
}
