package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Settings owns dossiers. Planning must not read, repair or delete even corrupt settings evidence. */
class PlanningDossierBoundaryTest {
    @Test fun planningWipePreservesSettingsOwnedDossierBytes() = runTest {
        for (raw in listOf("[]", "{broken", "saved dossier bytes")) {
            val storage = InMemoryKeyValueStore()
            storage.write("model-dossiers", raw)
            JsonPlanningRepository(storage, Json).wipe()
            assertEquals(raw, storage.read("model-dossiers"))
        }
    }

    @Test fun planningOwnerLoadAndWipeNeverReadsTheDossierKey() = runTest {
        val backing = InMemoryKeyValueStore()
        backing.write("model-dossiers", "{broken")
        val storage = object : io.aequicor.magicpaper.data.storage.KeyValueStore by backing {
            override fun read(key: String): String? {
                check(key != "model-dossiers") { "Planning cannot interpret settings data" }
                return backing.read(key)
            }
        }
        val owner = TestPlanningStore(JsonPlanningRepository(storage, Json))
        assertTrue(owner.plans().isEmpty())
        owner.wipe()
        assertEquals("{broken", backing.read("model-dossiers"))
    }
}
