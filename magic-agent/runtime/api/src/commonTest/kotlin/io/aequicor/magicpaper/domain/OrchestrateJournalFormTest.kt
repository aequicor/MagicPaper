package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.planning.OrchestrationEvent
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The form an orchestration event actually takes in the journal: nested in `CodingMachine.Intent.Orchestrate`.
 *
 * `OrchestrationEventWireFormatTest` in `core:model` pins every branch on its own. This pins the path the
 * journal really walks — an outer intent whose own name is `Orchestrate`, carrying the event under `event` —
 * so a change to how the two are combined, not just to a branch, is seen too. The string is the same
 * whether the branch names are pinned or not: this test passes unchanged against the code with every
 * `@SerialName` of `OrchestrationEvent` removed, which is what shows that pinning them changed nothing.
 */
class OrchestrateJournalFormTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stored = """{"type":"Orchestrate","session":{"id":"session","generation":3},""" +
        """"event":{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.PlanSelected","id":"plan"}}"""

    @Test fun anOrchestrateIntentIsWrittenAndReadInItsPinnedForm() {
        val intent: CodingMachine.Input = CodingMachine.Intent.Orchestrate(CodingMachine.SessionRef("session", 3), OrchestrationEvent.PlanSelected("plan"))
        assertEquals(stored, json.encodeToString(CodingMachine.Input.serializer(), intent))
        assertEquals(intent, json.decodeFromString(CodingMachine.Input.serializer(), stored))
    }
}
