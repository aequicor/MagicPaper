package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.domain.Plan
import io.aequicor.magicpaper.domain.DecisionCompiler
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JsonPlanningRepositoryIdentityTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val store = InMemoryKeyValueStore()
    private fun reopen() = JsonPlanningRepository(store, json)
    private fun plan(id: String = "plan", projectId: String = "project") = Plan(
        id = id, projectId = projectId, goal = "goal", createdAt = 1, updatedAt = 1,
    )
    private fun flat(vararg plans: Plan) = json.encodeToString(ListSerializer(Plan.serializer()), plans.toList())
    private fun checkpoint(projectId: String, plan: Plan?) = buildJsonObject {
        put("projectId", projectId)
        put("plan", plan?.let { json.encodeToJsonElement(Plan.serializer(), it) } ?: JsonNull)
    }.toString()
    private fun bytes() = store.keys("").associateWith { store.read(it) }

    @Test fun planCheckpointCannotRedirectItsKeyOrProjectEvenWhenAFlatSnapshotExists() = runTest {
        val cases = listOf(
            "coding-plan-v2-plan" to checkpoint("project", plan(id = "other")),
            "coding-plan-v2-plan" to checkpoint("other-project", plan()),
            "coding-plan-v2-plan" to checkpoint("other-project", plan(projectId = "other-project")),
            "coding-plan-v2-plan" to checkpoint("other-project", null),
        )
        cases.forEach { (key, raw) ->
            store.clear()
            store.write("coding-plans", flat(plan()))
            store.write(key, raw)
            val original = bytes()
            assertFailsWith<IllegalArgumentException> { reopen().plans() }
            assertEquals(original, bytes(), "Rejected identity must not rewrite the recovery evidence")
        }
    }

    @Test fun legacyProjectCheckpointCannotUseAnotherProjectsKeyOrPlan() = runTest {
        val cases = listOf(
            checkpoint("other-project", plan(projectId = "other-project")),
            checkpoint("project", plan(projectId = "other-project")),
            checkpoint("other-project", null),
        )
        cases.forEach { raw ->
            store.clear()
            store.write("coding-plans", flat(plan()))
            store.write("coding-plan-checkpoint-project", raw)
            val original = bytes()
            assertFailsWith<IllegalArgumentException> { reopen().plans() }
            assertEquals(original, bytes())
        }
    }

    @Test fun duplicateFlatIdsAreRejectedBeforeMapConversionInCurrentAndBackupSnapshots() = runTest {
        listOf(false, true).forEach { useBackup ->
            listOf(plan(), plan(projectId = "another-project")).forEach { duplicate ->
                store.clear()
                if (useBackup) store.write("coding-plans", "{broken")
                val key = if (useBackup) "coding-plans-backup" else "coding-plans"
                store.write(key, flat(plan(), duplicate))
                store.write("coding-plan-v2-plan", checkpoint("project", plan()))
                val original = bytes()
                assertFailsWith<IllegalArgumentException> { reopen().plans() }
                assertEquals(original, bytes(), "A checkpoint cannot make a duplicate imported identity valid")
            }
        }
    }

    @Test fun blankIdentitiesAreRejectedAtEveryImportedBoundary() = runTest {
        val cases = listOf(
            "coding-plans" to flat(plan(id = "")),
            "coding-plans" to flat(plan(projectId = " ")),
            "coding-plans-backup" to flat(plan(id = "")),
            "coding-plan-v2-" to checkpoint("project", null),
            "coding-plan-checkpoint-" to checkpoint("project", null),
            "coding-plan-v2-plan" to checkpoint("", null),
            "coding-plan-v2-plan" to checkpoint("project", plan(projectId = "")),
        )
        cases.forEach { (key, raw) ->
            store.clear()
            store.write(key, raw)
            val original = bytes()
            assertFailsWith<IllegalArgumentException> { reopen().plans() }
            assertEquals(original, bytes())
        }
    }

    @Test fun tombstonesRetainTheirExactPlanIdentityAndPreserveOldLookupAlias() = runTest {
        val first = plan("first")
        val second = plan("second")
        reopen().save(first)
        reopen().save(second)
        reopen().deletePlan("first")
        val tombstone = json.parseToJsonElement(store.read("coding-plan-v2-first")!!).jsonObject
        assertEquals("project", tombstone.getValue("projectId").jsonPrimitive.content)
        assertEquals(listOf("second"), reopen().plans().map { it.id })

        // Previous versions wrote deletePlan's plan-ID argument in projectId. Only a null payload permits it.
        store.write("coding-plans", flat(first, second))
        store.write("coding-plan-v2-first", checkpoint("first", null))
        val original = bytes()
        assertNull(reopen().planFor("first"))
        assertEquals(listOf("second"), reopen().plans().map { it.id })
        assertEquals(original, bytes())
    }

    @Test fun recoveryKeepsCorruptBytesAndUsesOnlyTheMatchingProjectBackup() = runTest {
        store.write("coding-plans", "{broken")
        store.write("coding-plans-backup", flat(plan()))
        store.write("coding-plan-checkpoint-project", "{broken checkpoint")
        val original = bytes()
        assertEquals(listOf(DecisionCompiler.migrate(plan())), reopen().plans())
        assertEquals(original, bytes())

        store.write("coding-plan-checkpoint-other-project", "{broken checkpoint")
        val changed = bytes()
        assertFailsWith<IllegalArgumentException> { reopen().plans() }
        assertEquals(changed, bytes())
    }

    @Test fun survivingCorruptBackupIsNotAnEmptyRepository() = runTest {
        store.write("coding-plans-backup", "{broken")
        assertFailsWith<IllegalStateException> { reopen().plans() }
        assertEquals("{broken", store.read("coding-plans-backup"))
    }

    @Test fun invalidSaveCannotWriteOrMoveAPlanToAnotherProject() = runTest {
        reopen().save(plan())
        val original = bytes()
        listOf(plan(id = ""), plan(projectId = ""), plan(projectId = "another-project")).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { reopen().save(invalid) }
            assertEquals(original, bytes())
        }
    }
}
