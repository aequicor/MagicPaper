package io.aequicor.magicpaper.data.browser

import io.aequicor.magicpaper.domain.tools.ToolArgumentRejection
import kotlinx.serialization.json.*
import nu.validator.validation.SimpleDocumentValidator
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.StringReader

/** Uses the validator's packaged schemas. Neither markup nor external entities are fetched over HTTP. */
internal object HtmlValidation {
    const val MAX_HTML = 2_000_000

    // Nu configures some schema flags through JVM properties; serialize its setup and validation.
    @Synchronized fun check(html: String): JsonObject {
        if (html.isBlank() || html.length > MAX_HTML) throw ToolArgumentRejection("HTML должен содержать от 1 до $MAX_HTML символов.")
        val messages = mutableListOf<JsonObject>()
        var errors = 0
        var warnings = 0
        val handler = object : ErrorHandler {
            private fun record(error: SAXParseException, severity: String) {
                if (severity == "warning") warnings++ else errors++
                if (messages.size < 100) messages += buildJsonObject {
                    put("severity", severity); put("line", error.lineNumber); put("column", error.columnNumber)
                    put("message", error.message.orEmpty().take(1000))
                }
            }
            override fun warning(exception: SAXParseException) = record(exception, "warning")
            override fun error(exception: SAXParseException) = record(exception, "error")
            override fun fatalError(exception: SAXParseException) = record(exception, "fatal")
        }
        val validator = SimpleDocumentValidator(false, false, false)
        validator.setUpMainSchema("http://s.validator.nu/html5-rdfalite.rnc", handler)
        validator.setUpValidatorAndParsers(handler, true, false)
        validator.checkHtmlInputSource(InputSource(StringReader(html)))
        return buildJsonObject {
            put("validator", "Nu Html Checker"); put("valid", errors == 0)
            put("errors", errors); put("warnings", warnings); put("messages", JsonArray(messages))
            put("truncated", errors + warnings > messages.size)
        }
    }
}
