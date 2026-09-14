package io.aequicor.magicpaper.designsystem

import java.awt.EventQueue

/** Match the desktop dispatcher; rendering from the test thread does not drain UI effects. */
internal fun <T> onPaperUi(block: () -> T): T {
    if (EventQueue.isDispatchThread()) return block()
    var result: Result<T>? = null
    EventQueue.invokeAndWait { result = runCatching(block) }
    return checkNotNull(result).getOrThrow()
}
