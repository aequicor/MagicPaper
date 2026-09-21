package io.aequicor.magicpaper.backend

import io.aequicor.magicpaper.domain.CodingEngine
import io.aequicor.magicpaper.domain.CodingEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class BackendAgentFactoryTest {
    @Test fun factoryConnectsOnlyImplementedProtocolsAndTheirCapabilities() {
        val protocols = createBackendAgentProtocols()
        assertEquals(setOf(CodingEngine.PI, CodingEngine.CODEX), protocols.descriptors.map { it.engine }.toSet())
        assertEquals(protocols.descriptors.size, protocols.descriptors.map { it.engine }.distinct().size)
        val pi = protocols.descriptor(CodingEngine.PI)
        assertEquals("Pi", pi.adapterName)
        assertContains(pi.capabilities, BackendAgentCapability.MANAGED_INSTALLATION)
        assertFalse(BackendAgentCapability.EXTERNAL_INSTALLATION in pi.capabilities)
        val codex = protocols.descriptor(CodingEngine.CODEX)
        assertContains(codex.capabilities, BackendAgentCapability.EXTERNAL_INSTALLATION)
        assertContains(codex.capabilities, BackendAgentCapability.NATIVE_TOOL_HISTORY)
        assertFalse(BackendAgentCapability.MANAGED_INSTALLATION in codex.capabilities)
        assertEquals(CodingEvent.SessionStarted("native"), protocols.pi.parse("""{"type":"session","id":"native"}"""))
        assertNull(protocols.codex.terminalToolResult(Json.parseToJsonElement("""{"type":"agentMessage","id":"message","text":"Done"}""").jsonObject))
    }
}
