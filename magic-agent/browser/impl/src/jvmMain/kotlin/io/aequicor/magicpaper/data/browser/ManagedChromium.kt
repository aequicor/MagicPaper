package io.aequicor.magicpaper.data.browser

import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.impl.driver.Driver
import io.aequicor.magicpaper.logging.AppLog
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Driver startup must not download Firefox or WebKit before Chromium can be opened. */
internal fun createManagedPlaywright(): Playwright = Playwright.create(
    Playwright.CreateOptions().setEnv(mapOf("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1")))

/** Playwright ships its driver with the Java dependency, but keeps Chromium in a separate cache.
 * Install the browser matching this exact Playwright version only when launch proves it is missing. */
internal fun <T> launchManagedChromium(launch: () -> T, install: () -> Unit = ::installManagedChromium): T {
    val observedInstall = synchronized(installLock) { installGeneration }
    try { return launch() }
    catch (missing: PlaywrightException) {
        if (!missing.message.orEmpty().contains("Executable doesn't exist", ignoreCase = true)) throw missing
        return synchronized(installLock) {
            if (installGeneration == observedInstall) {
                install()
                installGeneration++
            }
            try { launch() }
            catch (stillMissing: PlaywrightException) {
                if (stillMissing.message.orEmpty().contains("Executable doesn't exist", ignoreCase = true))
                    throw BrowserInstallFailed("Chromium установлен, но недоступен приложению. Проверьте права доступа и повторите открытие.", stillMissing)
                throw stillMissing
            }
        }
    }
}

private val installLock = Any()
private var installGeneration = 0L

internal class BrowserInstallFailed(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

internal fun installManagedChromium() {
    val process = try {
        // Playwright's CLI uses this driver process builder before appending its arguments.
        // Reuse it here: packaged apps do not expose a reliable Java child classpath.
        Driver.ensureDriverInstalled(emptyMap(), false).createProcessBuilder().apply {
            command().addAll(listOf("install", "chromium"))
            redirectErrorStream(true)
            redirectOutput(ProcessBuilder.Redirect.DISCARD)
        }.start()
    } catch (failure: Exception) {
        AppLog.error("coding.browser", "install.start.failed", mapOf("causeType" to failure.javaClass.simpleName))
        throw BrowserInstallFailed("Не удалось запустить установку Chromium. Проверьте права приложения и повторите открытие.", failure)
    }
    try {
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            AppLog.error("coding.browser", "install.timed_out", emptyMap())
            throw BrowserInstallFailed("Установка Chromium не завершилась. Проверьте подключение и повторите открытие.")
        }
        if (process.exitValue() != 0) {
            AppLog.error("coding.browser", "install.failed", mapOf("exitCode" to process.exitValue().toString()))
            throw BrowserInstallFailed("Не удалось установить Chromium. Проверьте подключение и повторите открытие.")
        }
        AppLog.info("coding.browser", "install.completed")
    } catch (interrupted: InterruptedException) {
        process.destroyForcibly()
        Thread.currentThread().interrupt()
        throw CancellationException("Установка Chromium прервана").also { it.initCause(interrupted) }
    }
}
