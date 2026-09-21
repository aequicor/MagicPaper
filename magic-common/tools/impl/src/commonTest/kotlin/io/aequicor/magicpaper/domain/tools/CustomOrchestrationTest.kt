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
    // The tools that carry organism orchestration authority, pinned by id. The invariant is the set,
    // not the session. prefix: ToolCatalog's session.manage carries the prefix and no flag on purpose.
    // It is the ORCHESTRATOR plan-stage command, addressed by stageId inside an existing plan, and
    // OrchestrationService refuses it outright for an organism-managed session, directing the caller
    // to session.control instead (OrchestrationService.changeSession). Slice 4 replaced a post-hoc
    // prefix rule with an explicit flag on each entry; this set is the net that rule used to be.
    private val orchestrationTools = setOf(
        "session.control", "session.create", "session.delete", "session.integration.get",
        "session.result.get", "session.result.review", "session.results.integrate", "session.route",
        "session.send", "session.wait")
    @Test fun exactlyThePinnedToolsDeclareOrchestration() {
        assertEquals(orchestrationTools, SessionToolCatalog.definitions.filter { it.orchestration }.map { it.id }.toSet(),
            "SessionToolCatalog's orchestration tools no longer match the pin: a flag was dropped, a tool was added, " +
            "or an orchestration tool moved to another catalogue. Change the set above only together with the " +
            "authority change it records.")
        assertEquals(orchestrationTools, ToolCatalog.definitions.filter { it.orchestration }.map { it.id }.toSet(),
            "The orchestration tools across both catalogues no longer match the pin. A tool declaring " +
            "orchestration = true is advertised only to PLANNING sessions and dispatched with authority over the " +
            "organism session tree: grant that deliberately here, or drop the flag.")
    }
    // Still earns its place next to the pin: the pin cannot see a NEW session.* tool that forgets the
    // flag, because a tool absent from both the set and the catalogue filter leaves the pin green.
    @Test fun everySessionScopedToolDeclaresOrchestration() {
        val missing = SessionToolCatalog.definitions.filter { it.id.startsWith("session.") && !it.orchestration }.map { it.id }
        assertTrue(missing.isEmpty(), "$missing are session.* tools without orchestration = true, so they are advertised " +
            "and dispatchable in CODE and RESEARCH sessions. Add orchestration = true to each entry in SessionToolCatalog.")
    }
    @Test fun deleteIsExplicitlyDestructiveAndRequiresATarget() {
        val definition = SessionToolCatalog.definitions.single { it.id == "session.delete" }
        assertTrue(definition.mutating)
        assertTrue(definition.protocolDefinition().toString().contains("\"destructiveHint\":true"))
        assertTrue(definition.schema.toString().contains("sessionId"))
    }
}
