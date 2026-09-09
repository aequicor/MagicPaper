package io.aequicor.magicpaper.domain

import kotlinx.serialization.json.Json
import kotlin.test.*

class CodingInteractionModeTest {
    private val session = CodingSession("s", "p", "Task", 1, piSessionId = "old-native", engine = CodingEngine.PI)
    @Test fun legacySessionsAndRequestsRetainTheirMode() {
        val old = """{"id":"s","projectId":"p","name":"Task","createdAt":1,"planningMode":true}"""
        assertEquals(CodingInteractionMode.PLANNING, Json.decodeFromString<CodingSession>(old).interactionMode)
        assertFalse(Json.decodeFromString<CodingSession>(old).researchMode)
        val research = session.copy(researchMode = true, pendingRun = CodingRunCheckpoint("m", "Read"))
        assertEquals(CodingInteractionMode.RESEARCH, research.forPendingRun().interactionMode)
        assertEquals(research, Json.decodeFromString<CodingSession>(Json.encodeToString(research)))
    }
    @Test fun transitionsResetNativePermissionsAndCloseStoppedRequests() {
        val stopped = session.copy(pendingRun = CodingRunCheckpoint("m", "Old", intent = ExecutionIntent.STOP))
        val research = stopped.changeInteractionMode(CodingInteractionMode.RESEARCH)
        assertTrue(research.researchMode); assertFalse(research.planningMode)
        assertTrue(research.needsHistorySeed); assertEquals("", research.piSessionId); assertNull(research.pendingRun)
        val ordinary = research.changeInteractionMode(CodingInteractionMode.CODE)
        assertFalse(ordinary.researchMode)
        val planning = research.changeInteractionMode(CodingInteractionMode.PLANNING)
        assertFalse(planning.researchMode); assertTrue(planning.planningMode)
        assertFailsWith<IllegalArgumentException> { planning.changeInteractionMode(CodingInteractionMode.RESEARCH) }
        assertFailsWith<IllegalArgumentException> { planning.changeInteractionMode(CodingInteractionMode.CODE) }
    }
    @Test fun runningWorkersArchivedAndMismatchedCheckpointsCannotChangeOrWeakenPermissions() {
        for (s in listOf(session.copy(stageId = "stage"), session.copy(archived = true), session.copy(pendingRun = CodingRunCheckpoint("m", "Run")))) {
            assertFailsWith<IllegalArgumentException> { s.changeInteractionMode(CodingInteractionMode.RESEARCH) }
        }
        assertFailsWith<IllegalArgumentException> { session.changeInteractionMode(CodingInteractionMode.RESEARCH, busy = true) }
        assertFailsWith<IllegalArgumentException> { session.copy(planningMode = true, researchMode = true).forPendingRun() }
        assertFailsWith<IllegalArgumentException> {
            session.copy(pendingRun = CodingRunCheckpoint("m", "Read", interactionMode = CodingInteractionMode.RESEARCH)).forPendingRun()
        }
    }
    @Test fun modeHistorySeedExcludesSystemReceiptsAndCurrentRequestAndIsBounded() {
        val history = listOf(CodingMessage("context", CodingRole.AGENT, "secret instructions", createdAt = 1, systemContext = true),
            CodingMessage("first", CodingRole.USER, "explain source.kt", createdAt = 2),
            CodingMessage("reply", CodingRole.AGENT, "observations", createdAt = 3),
            CodingMessage("current", CodingRole.USER, "current request", createdAt = 4))
        val seed = researchContextSeed(history, 10, "current")
        assertContains(seed, "explain source.kt"); assertContains(seed, "observations")
        assertFalse(seed.contains("secret instructions")); assertFalse(seed.contains("current request"))
        assertTrue(researchContextSeed(List(200) { CodingMessage("$it", CodingRole.USER, "a".repeat(20_000), createdAt = 1) }, 200, "new").length < 61_000)
    }
}
