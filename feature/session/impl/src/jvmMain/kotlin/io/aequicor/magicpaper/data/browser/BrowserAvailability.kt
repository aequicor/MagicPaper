package io.aequicor.magicpaper.data.browser

import io.aequicor.magicpaper.domain.tools.ToolStateRejection
import io.aequicor.magicpaper.logging.AppLog

/** Owned by an engine runtime. A broken installation must not launch a driver on every model retry. */
class BrowserAvailability internal constructor() {
    @Volatile private var failure: String? = null
    internal val available: Boolean get() = failure == null

    @Synchronized internal fun <T> start(phase: String = "startup", block: () -> T): T {
        failure?.let { throw ToolStateRejection(it) }
        try { return block() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            val message = "Встроенный браузер недоступен. Исправьте установку Chromium/Playwright и перезапустите MagicPaper. " +
                "Повторный вызов в этом запуске не поможет. Не меняйте выбранный пользователем способ работы без его согласия."
            failure = message
            // Native exceptions can contain paths, URLs and environment values.
            AppLog.error("coding.browser", "launch.failed", mapOf("causeType" to error.javaClass.simpleName,
                "phase" to phase, "result" to "disabled_until_restart"))
            throw ToolStateRejection(message)
        }
    }
}
