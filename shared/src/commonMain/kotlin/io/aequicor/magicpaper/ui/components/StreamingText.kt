package io.aequicor.magicpaper.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos

private const val STREAM_FRAME_NANOS = 32_000_000L
private const val CATCH_UP_MILLIS = 160.0
private const val MIN_CHARS_PER_MILLI = 0.06

/** Only new suffixes are animated: opening an existing answer never replays its history. */
@Composable
internal fun rememberStreamingText(text: String, streaming: Boolean): String {
    val target by rememberUpdatedState(text)
    var displayed by remember { mutableStateOf(text) }
    LaunchedEffect(streaming) {
        val buffer = StreamingTextBuffer(displayed)
        snapshotFlow { target }.collect { next ->
            buffer.update(next, streaming)
            displayed = buffer.text
            if (buffer.hasPendingText) {
                var previousFrame = withFrameNanos { it }
                while (buffer.hasPendingText) {
                    val frame = withFrameNanos { it }
                    val elapsed = frame - previousFrame
                    if (elapsed < STREAM_FRAME_NANOS) continue
                    // Read the newest target without restarting the clock on every network chunk.
                    // This also coalesces bursts before asking Markdown to parse again.
                    buffer.update(target, streaming)
                    buffer.advance(elapsed / 1_000_000.0)
                    displayed = buffer.text
                    previousFrame = frame
                }
            }
        }
    }
    // Completion and authoritative replacements must not wait for an animation frame.
    return if (!streaming || !text.startsWith(displayed)) text else displayed
}

/** A burst catches up in ~160 ms; continuous input increases the reveal rate instead of queuing. */
internal class StreamingTextBuffer(initialText: String) {
    private var target = initialText
    private var position = initialText.length.toDouble()
    private var charsPerMilli = MIN_CHARS_PER_MILLI

    val hasPendingText: Boolean get() = position < target.length

    val text: String
        get() {
            var end = position.toInt().coerceAtMost(target.length)
            // A UTF-16 surrogate pair (for example an emoji) must appear in one update.
            if (end > 0 && end < target.length && target[end - 1].isHighSurrogate() && target[end].isLowSurrogate()) end--
            return target.take(end)
        }

    fun update(value: String, streaming: Boolean) {
        if (!streaming || !value.startsWith(target)) {
            target = value
            position = value.length.toDouble()
        } else if (value != target) {
            target = value
            charsPerMilli = ((target.length - position) / CATCH_UP_MILLIS).coerceAtLeast(MIN_CHARS_PER_MILLI)
        }
    }

    fun advance(elapsedMillis: Double) {
        position = (position + elapsedMillis.coerceAtLeast(0.0) * charsPerMilli).coerceAtMost(target.length.toDouble())
    }
}
