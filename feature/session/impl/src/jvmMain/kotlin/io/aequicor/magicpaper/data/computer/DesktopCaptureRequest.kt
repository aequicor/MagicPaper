package io.aequicor.magicpaper.data.computer

import kotlinx.serialization.json.JsonObject

internal enum class ScreenshotResolution(val wireName: String) { OVERVIEW("overview"), NATIVE("native") }

/** Logical pixels relative to one display. A frame keeps this viewport to translate later input. */
internal data class DesktopRegion(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun validate(display: ComputerDisplay) {
        require(x >= 0 && y >= 0 && width > 0 && height > 0 &&
            x.toLong() + width <= display.width && y.toLong() + height <= display.height) { "Область выходит за границы экрана" }
    }

    fun crop(args: JsonObject, imageWidth: Int, imageHeight: Int): DesktopRegion {
        require(args.keys == setOf("x", "y", "width", "height")) { "region: требуются x, y, width, height" }
        val left = args.requiredInt("x"); val top = args.requiredInt("y")
        val w = args.requiredInt("width"); val h = args.requiredInt("height")
        require(left >= 0 && top >= 0 && w > 0 && h > 0 && left.toLong() + w <= imageWidth && top.toLong() + h <= imageHeight) {
            "region: область должна находиться внутри последнего снимка"
        }
        // Round outward to whole logical pixels, retaining all selected detail on HiDPI displays.
        val x1 = (left.toLong() * width / imageWidth).toInt()
        val y1 = (top.toLong() * height / imageHeight).toInt()
        val x2 = ((left.toLong() + w) * width + imageWidth - 1) / imageWidth
        val y2 = ((top.toLong() + h) * height + imageHeight - 1) / imageHeight
        return DesktopRegion(x + x1, y + y1, x2.toInt() - x1, y2.toInt() - y1)
    }

    companion object { fun full(display: ComputerDisplay) = DesktopRegion(0, 0, display.width, display.height) }
}

internal data class DesktopCaptureRequest(val region: DesktopRegion, val resolution: ScreenshotResolution = ScreenshotResolution.OVERVIEW)

// Bound native allocations and protocol payloads; request a region when a whole display exceeds this.
internal const val MAX_NATIVE_SCREENSHOT_PIXELS = 16_777_216L
