package io.aequicor.magicpaper.data.browser

import io.aequicor.magicpaper.domain.tools.ToolArgumentRejection
import kotlinx.serialization.json.*
import kotlin.test.*

class HtmlValidationTest {
    @Test fun validatesMarkupWithLocalSchemaAndReportsPositions() {
        assertTrue(HtmlValidation.check(VALID_HTML)["valid"]!!.jsonPrimitive.boolean)
        val invalid = HtmlValidation.check(VALID_HTML.replace("<h1>Fixture</h1>", "<p>One<div>Two</div></p><img src='test.png'>"))
        assertFalse(invalid["valid"]!!.jsonPrimitive.boolean)
        assertTrue(invalid["errors"]!!.jsonPrimitive.int > 0)
        assertTrue(invalid["messages"]!!.jsonArray.any { it.jsonObject["line"]!!.jsonPrimitive.int > 0 })
        assertTrue(HtmlValidation.check(VALID_HTML)["valid"]!!.jsonPrimitive.boolean, "Earlier errors must not leak into the next document")
    }

    @Test fun inputAndDiagnosticsAreBoundedWithoutHidingErrors() {
        assertFailsWith<ToolArgumentRejection> { HtmlValidation.check("") }
        assertFailsWith<ToolArgumentRejection> { HtmlValidation.check("x".repeat(HtmlValidation.MAX_HTML + 1)) }
        val result = HtmlValidation.check(VALID_HTML.replace("<h1>Fixture</h1>", "<img src='test.png'>".repeat(150)))
        assertFalse(result["valid"]!!.jsonPrimitive.boolean)
        assertTrue(result["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(100, result["messages"]!!.jsonArray.size)
    }
}
