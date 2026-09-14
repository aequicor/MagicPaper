package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import kotlin.test.*

class CustomOrchestrationTest {
    @Test fun onlyPlanningSessionsReceiveOrchestrationAndImmunityRemainsAvailable() {
        for (mode in CodingInteractionMode.entries) for (role in ToolRole.entries) {
            val context = ToolExecutionContext("project", "session", "session", "request", role, mode)
            val orchestration = SessionToolCatalog.definitions.filter { it.orchestration }
            assertTrue(orchestration.isNotEmpty())
            assertEquals(mode == CodingInteractionMode.PLANNING, orchestration.all { it.allowed(context) })
            assertTrue(SessionToolCatalog.definitions.single { it.id == "immunity.signal" }.allowed(context))
        }
    }
    @Test fun deleteIsExplicitlyDestructiveAndRequiresATarget() {
        val definition = SessionToolCatalog.definitions.single { it.id == "session.delete" }
        assertTrue(definition.mutating)
        assertTrue(definition.protocolDefinition().toString().contains("\"destructiveHint\":true"))
        assertTrue(definition.schema.toString().contains("sessionId"))
    }
}
