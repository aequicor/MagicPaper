package io.aequicor.magicpaper.data.browser

import com.microsoft.playwright.Page
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.serialization.json.*

/** Page.evaluate has no timeout. Chromium's execution deadline also terminates a synchronous loop. */
internal fun evaluateBrowserScript(page: Page, script: String, timeoutMs: Int = 15_000): JsonObject {
    val client = page.context().newCDPSession(page)
    var primary: Throwable? = null
    try {
        val expression = """
            (() => {
              let value = (0, eval)(${JsonPrimitive(script)});
              if (typeof value === 'function') value = value();
              let timer;
              return Promise.race([
                Promise.resolve(value),
                new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Evaluation timed out')), $timeoutMs); })
              ]).then(value => {
                const json = JSON.stringify(value) ?? 'null';
                return {result: json.slice(0, 64000), truncated: json.length > 64000};
              }).finally(() => clearTimeout(timer));
            })()
        """.trimIndent()
        val parameters = com.google.gson.JsonObject().apply {
            addProperty("expression", expression)
            addProperty("returnByValue", true)
            addProperty("awaitPromise", true)
            addProperty("timeout", timeoutMs)
        }
        val response = client.send("Runtime.evaluate", parameters)
        if (response.has("exceptionDetails")) {
            // Exception details contain the supplied script and arbitrary page content.
            throw IllegalStateException("JavaScript не завершился успешно. Проверьте скрипт и состояние страницы перед повтором.")
        }
        return Json.parseToJsonElement(response.getAsJsonObject("result").get("value").toString()).jsonObject
    } catch (error: Throwable) {
        primary = error
        throw error
    } finally {
        try { client.detach() }
        catch (error: Exception) {
            AppLog.error("coding.browser", "evaluation.detach_failed", mapOf("causeType" to error.javaClass.simpleName))
            if (primary != null) primary.addSuppressed(error)
            else throw IllegalStateException("Не удалось завершить проверку страницы. Проверьте состояние браузера перед повтором.", error)
        }
    }
}
