package io.aequicor.magicpaper.data.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class CodexProtocolBoundaryTest {
    @Test fun aPlainAgentMessageIsNotATerminalToolResult() {
        assertNull(CodexNativeAdapter().terminalToolResult(
            Json.parseToJsonElement("""{"type":"agentMessage","id":"message","text":"Done"}""").jsonObject))
    }
}
