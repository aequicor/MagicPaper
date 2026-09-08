package io.aequicor.magicpaper.ui.components

/**
 * Bound the input to text layout, not just its visible lines: shaping a long shell
 * command or file payload is expensive even when Text has maxLines = 2.
 */
internal fun codingToolPreview(title: String): String {
    val limit = minOf(title.length, 512)
    var end = 0
    var lines = 1
    while (end < limit) {
        val char = title[end]
        if (char == '\n' || char == '\r') {
            if (lines == 2) break
            lines++
            if (char == '\r' && end + 1 < limit && title[end + 1] == '\n') end++
        }
        end++
    }
    if (end == title.length) return title
    // Do not leave half an emoji at the character budget boundary.
    if (end > 0 && title[end - 1].isHighSurrogate() && title[end].isLowSurrogate()) end--
    return title.substring(0, end).trimEnd() + "…"
}
