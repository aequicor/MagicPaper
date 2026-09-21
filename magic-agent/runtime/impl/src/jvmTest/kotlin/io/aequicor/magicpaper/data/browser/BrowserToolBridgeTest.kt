package io.aequicor.magicpaper.data.browser

import io.aequicor.magicpaper.data.tools.AgentToolBridge
import io.aequicor.magicpaper.data.tools.PiAgentToolExtension
import io.aequicor.magicpaper.data.tools.toolResultContent
import io.aequicor.magicpaper.domain.CodingInteractionMode
import io.aequicor.magicpaper.domain.browser.BrowserSession
import io.aequicor.magicpaper.domain.tools.*
import kotlinx.serialization.json.*
import kotlin.test.*

internal fun fakeBrowserSession(): BrowserSession = object : BrowserSession {
    override val commands = listOf(JsonToolCommand(ToolDefinition("browser.validate_html", "Test browser port", buildJsonObject {})) { _, _, _ -> JsonNull })
    override fun close() = Unit
}

class BrowserToolBridgeTest {
    @Test fun bridgeAttachesBrowserPortAndPiListsItUntilClose() {
        val session = testToolSessions(MemoryToolReceiptStore()).session(ToolExecutionContext("p", "s", "s", "r", ToolRole.CHAT, CodingInteractionMode.CODE))
        var closed = false
        val browser = object : BrowserSession by fakeBrowserSession() { override fun close() { closed = true } }
        AgentToolBridge(session, browser).use {
            assertTrue(session.definitions.any { it.id == "browser.validate_html" })
            assertTrue("magicpaper_browser_validate_html" in PiAgentToolExtension.source(session))
        }
        assertTrue(closed)
        assertTrue(session.definitions.none { it.id.startsWith("browser.") })
    }

    @Test fun auxiliaryRunsDoNotGainBrowserSideEffects() {
        val session = testToolSessions(MemoryToolReceiptStore()).session(ToolExecutionContext("p", "s", "s", "r",
            ToolRole.CHAT, CodingInteractionMode.CODE, auxiliaryExecution = true))
        AgentToolBridge(session, fakeBrowserSession()).use {
            assertTrue(session.definitions.none { it.id.startsWith("browser.") })
        }
    }

    @Test fun onlyScreenshotResultsCanEmitMcpImages() {
        val result = buildJsonObject {
            put("tabId", "tab-1")
            put("image", buildJsonObject { put("type", "image"); put("mimeType", "image/png"); put("data", "test") })
        }
        val blocks = toolResultContent("magicpaper_browser_screenshot", result)
        assertEquals(listOf("text", "image"), blocks.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertFalse("data" in blocks.first().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(1, toolResultContent("browser.evaluate", result).size)
    }
}
